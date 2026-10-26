package com.smartorder.edu.agent.repository.impl;

import com.smartorder.edu.agent.mapper.AgentTaskRecordMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.AgentTaskRecord;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.repository.AgentTaskRepository;
import com.smartorder.edu.agent.runtime.AgentTaskMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 对话任务元数据仓储的 MyBatis 实现——MySQL {@code agent_chat_task} 是任务的权威来源。
 *
 * <p>Redis 只保存事件片段（Stream），不再保存任务状态。查询状态、刷新恢复、
 * 中断收敛都以本仓储为准；只有事件正文的补发需要读 Redis。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MyBatisAgentTaskRepository implements AgentTaskRepository {

    /** 未绑定消息的占位任务超过该时长仍未开始，视为进程在事务 A 之前退出 */
    private static final long UNBOUND_STALE_MS = 30_000L;

    private final AgentTaskRecordMapper mapper;

    private final ConversationMessageMapper messageMapper;

    /**
     * 幂等预留任务行。
     *
     * <p>先用 {@code (user_id, client_request_id)} 唯一键抢占；命中已有任务时：</p>
     * <ul>
     *   <li>已绑定 assistant 消息或已终态：真正的重试，直接复用，不再插入消息；</li>
     *   <li>未绑定且刚创建：另一个请求正在 openReply，返回 created=false，避免重复插消息；</li>
     *   <li>未绑定且已超时：说明上次进程在 openReply 前退出，删掉占位行后重新创建。</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Reservation reserve(AgentTaskMeta candidate) {
        try {
            mapper.insert(toRecord(candidate));
            return new Reservation(candidate, true);
        } catch (DuplicateKeyException duplicate) {
            AgentTaskMeta existing = findByClientRequest(candidate.getUserId(), candidate.getClientRequestId());
            if (existing == null) {
                throw duplicate;
            }
            if (existing.getAssistantMessageId() != null || existing.statusEnum().isTerminal()) {
                return new Reservation(existing, false);
            }
            long age = System.currentTimeMillis() - existing.getUpdatedAt();
            if (age < UNBOUND_STALE_MS) {
                return new Reservation(existing, false);
            }
            // 残留占位：如果 openReply 已经落库但 bindTurn 没跑完，消息行还在，
            // 不能删任务重建（会插出第二对消息）；先把已有消息重新绑回任务行。
            if (rebindMessages(existing)) {
                AgentTaskMeta rebound = toMeta(mapper.selectByTaskId(existing.getTaskId()));
                return new Reservation(rebound != null ? rebound : existing, false);
            }
            // 没有消息残留：复用原 taskId，先删后插，保证同一幂等键仍只有一个任务。
            mapper.deleteUnbound(existing.getTaskId());
            candidate.setTaskId(existing.getTaskId());
            try {
                mapper.insert(toRecord(candidate));
                return new Reservation(candidate, true);
            } catch (DuplicateKeyException retryConflict) {
                AgentTaskMeta latest = findByClientRequest(candidate.getUserId(), candidate.getClientRequestId());
                return new Reservation(latest != null ? latest : existing, false);
            }
        }
    }

    @Override
    public AgentTaskMeta find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return toMeta(mapper.selectByTaskId(taskId));
    }

    @Override
    public AgentTaskMeta findByClientRequest(Long userId, String clientRequestId) {
        if (userId == null || clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return toMeta(mapper.selectByClientRequest(userId, clientRequestId));
    }

    @Override
    public AgentTaskMeta findLatestByConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return toMeta(mapper.selectLatestByConversation(conversationId));
    }

    /**
     * 事务 A 完成后回填会话与消息 ID。
     */
    @Override
    public void bindTurn(String taskId, String conversationId, String traceId,
                         Long userMessageId, Long assistantMessageId,
                         Integer assistantSequenceNo, String title) {
        mapper.bindTurn(taskId, conversationId, traceId, userMessageId,
                assistantMessageId, assistantSequenceNo, title);
    }

    @Override
    public void markRunning(String taskId) {
        mapper.markRunning(taskId);
    }

    @Override
    public void updateTerminalState(String taskId, AgentTaskStatus status, long lastSeq,
                                    String errorMessage, Long assistantMessageId, boolean replayable) {
        mapper.updateTerminalState(taskId, status.name(), lastSeq, errorMessage,
                assistantMessageId, replayable);
    }

    @Override
    public void markReplayDegraded(String taskId) {
        try {
            mapper.markReplayDegraded(taskId);
        } catch (Exception e) {
            log.warn("[TaskRepository] 回放降级标记写入失败: taskId={}, reason={}", taskId, e.getMessage());
        }
    }

    /**
     * 删除还没有绑定消息的占位任务（事务 A 失败或进程在 openReply 前退出）。
     * 如果 openReply 已落库但 bindTurn 没跑完，则先把消息重新绑回任务行，绝不删除。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void discardUnbound(String taskId) {
        AgentTaskMeta existing = find(taskId);
        if (existing != null && rebindMessages(existing)) {
            return;
        }
        mapper.deleteUnbound(taskId);
    }

    /**
     * @return true 表示找到了尚未绑定的 assistant 消息并把任务行补全
     */
    private boolean rebindMessages(AgentTaskMeta existing) {
        List<ConversationMessage> messages = messageMapper.selectByTaskId(existing.getTaskId());
        ConversationMessage assistant = messages.stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .findFirst()
                .orElse(null);
        if (assistant == null) {
            return false;
        }
        ConversationMessage user = messages.stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElse(null);
        mapper.bindTurn(existing.getTaskId(), assistant.getConversationId(),
                existing.getTraceId() != null ? existing.getTraceId() : assistant.getTraceId(),
                user != null ? user.getId() : null,
                assistant.getId(), assistant.getSequenceNo(), existing.getTitle());
        log.info("[TaskRepository] 重新绑定已落库消息: taskId={}, assistantMsgId={}",
                existing.getTaskId(), assistant.getId());
        return true;
    }

    /**
     * 进程在 openReply 之前退出留下的未绑定任务；定时清扫使用。
     */
    @Override
    public List<AgentTaskMeta> findStaleReservations() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(UNBOUND_STALE_MS / 1000);
        return mapper.selectStaleReservations(threshold).stream().map(this::toMeta).toList();
    }

    private AgentTaskRecord toRecord(AgentTaskMeta meta) {
        AgentTaskRecord record = new AgentTaskRecord();
        record.setTaskId(meta.getTaskId());
        record.setConversationId(meta.getConversationId());
        record.setUserId(meta.getUserId());
        record.setUserMessageId(meta.getUserMessageId());
        record.setAssistantMessageId(meta.getAssistantMessageId());
        record.setAssistantSequenceNo(meta.getAssistantSequenceNo());
        record.setClientRequestId(meta.getClientRequestId());
        record.setTraceId(meta.getTraceId());
        record.setStatus(meta.getStatus());
        record.setLastSeq(Math.max(0L, meta.getLastSeq()));
        record.setTitle(meta.getTitle());
        record.setErrorMessage(meta.getErrorMessage());
        record.setReplayable(meta.isReplayable());
        LocalDateTime now = LocalDateTime.now();
        record.setCreatedAt(meta.getCreatedAt() > 0 ? toLocalDateTime(meta.getCreatedAt()) : now);
        record.setUpdatedAt(meta.getUpdatedAt() > 0 ? toLocalDateTime(meta.getUpdatedAt()) : now);
        record.setFinishedAt(meta.getFinishedAt() > 0 ? toLocalDateTime(meta.getFinishedAt()) : null);
        return record;
    }

    private AgentTaskMeta toMeta(AgentTaskRecord record) {
        if (record == null) {
            return null;
        }
        return AgentTaskMeta.builder()
                .taskId(record.getTaskId())
                .conversationId(record.getConversationId())
                .userId(record.getUserId())
                .traceId(record.getTraceId())
                .clientRequestId(record.getClientRequestId())
                .status(record.getStatus())
                .createdAt(toEpochMillis(record.getCreatedAt()))
                .updatedAt(toEpochMillis(record.getUpdatedAt()))
                .finishedAt(toEpochMillis(record.getFinishedAt()))
                .lastSeq(record.getLastSeq() == null ? 0L : record.getLastSeq())
                .userMessageId(record.getUserMessageId())
                .assistantMessageId(record.getAssistantMessageId())
                .assistantSequenceNo(record.getAssistantSequenceNo())
                .title(record.getTitle())
                .errorMessage(record.getErrorMessage())
                .replayable(!Boolean.FALSE.equals(record.getReplayable()))
                .build();
    }

    private long toEpochMillis(LocalDateTime value) {
        return value == null ? 0L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
