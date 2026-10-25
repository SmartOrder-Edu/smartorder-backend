package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.mapper.AgentChatTaskMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentTaskCreateRequest;
import com.smartorder.edu.agent.model.entity.AgentChatTask;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.vo.AgentCacheScopeVO;
import com.smartorder.edu.agent.model.vo.AgentTaskMessageVO;
import com.smartorder.edu.agent.model.vo.AgentTaskReceiptVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSnapshotVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSummaryVO;
import com.smartorder.edu.agent.orchestrator.AgentOrchestrator;
import com.smartorder.edu.agent.service.AgentTaskService;
import com.smartorder.edu.agent.stream.AgentEventLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 任务生命周期实现：幂等创建、后台执行、快照投影与遗留任务收敛。
 * <p>
 * 关键约束（改动前务必确认）：
 * 1) 先落库再调度：HTTP 响应不等待首个模型事件，重复 POST 由唯一索引仲裁；
 * 2) 先提交 MySQL 终态再发 Redis 终态事件：终态事件丢失时快照仍能兜底；
 * 3) 快照正文与事件游标取自同一行，绝不允许快照超前于已发布事件。
 */
@Slf4j
@Service
public class AgentTaskServiceImpl implements AgentTaskService {

    /** requestId 上限与库表定义保持一致。 */
    private static final int MAX_REQUEST_ID_LENGTH = 100;
    /** 任务世代前缀；世代标识必须能从 taskId 反推，便于遗留任务补发终态事件。 */
    private static final String STREAM_VERSION_PREFIX = "v1-";

    private final AgentChatTaskMapper taskMapper;
    private final ConversationMessageMapper messageMapper;
    private final AgentOrchestrator orchestrator;
    private final AgentEventLog eventLog;
    private final ExecutorService taskExecutor;

    public AgentTaskServiceImpl(AgentChatTaskMapper taskMapper,
                                ConversationMessageMapper messageMapper,
                                AgentOrchestrator orchestrator,
                                AgentEventLog eventLog,
                                @Qualifier("agentTaskExecutor") ExecutorService taskExecutor) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
        this.orchestrator = orchestrator;
        this.eventLog = eventLog;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public AgentTaskReceiptVO create(AgentTaskCreateRequest request, AgentCaller caller) {
        validate(request);

        // 摘要基于内部请求体计算：与调度实际执行的入参同源，重试才能稳定命中幂等键。
        AgentChatRequest chatRequest = toChatRequest(request, caller.userId());
        String requestHash = digest(JSON.toJSONString(chatRequest));

        String taskId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String conversationId = isBlank(request.getConversationId())
                ? UUID.randomUUID().toString() : request.getConversationId();
        String streamVersion = STREAM_VERSION_PREFIX + taskId;

        AgentChatTask task = new AgentChatTask();
        task.setTaskId(taskId);
        task.setMessageId(messageId);
        task.setConversationId(conversationId);
        task.setUserId(caller.userId());
        task.setTenantId(caller.tenantId());
        task.setRequestId(request.getRequestId());
        task.setRequestHash(requestHash);
        task.setStatus(AgentChatTask.STATUS_QUEUED);
        task.setStreamVersion(streamVersion);

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            return reuseExistingTask(request, caller, requestHash);
        }

        // 服务端标识在落库后才注入编排入参，客户端无法指定。
        chatRequest.setConversationId(conversationId);
        chatRequest.setTaskId(taskId);
        chatRequest.setMessageId(messageId);

        AgentTaskReceiptVO receipt = AgentTaskReceiptVO.from(task);
        try {
            // 先缓存确认标识，再调度后台 Agent；HTTP 响应无需等待首个模型事件。
            eventLog.cacheReceipt(taskId, receiptFields(receipt));
            taskExecutor.execute(() -> runTask(chatRequest));
        } catch (RuntimeException failure) {
            taskMapper.markDispatchFailed(taskId);
            // 调度失败时覆盖排队确认缓存，避免客户端读到永远不会执行的任务。
            cacheFailedReceipt(taskId, receipt, failure);
            if (failure instanceof RejectedExecutionException rejected) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 任务队列已满", rejected);
            }
            throw failure;
        }
        return receipt;
    }

    @Override
    public AgentTaskSnapshotVO snapshot(String taskId, AgentCaller caller) {
        AgentChatTask task = requireOwned(taskId, caller);
        boolean terminal = AgentChatTask.isTerminal(task.getStatus());
        List<ConversationMessage> messages = messageMapper.selectAssistantByTraceId(taskId, caller.userId());
        ConversationMessage latest = messages.isEmpty() ? null : messages.get(messages.size() - 1);

        String saved = latest == null || latest.getContent() == null ? "" : latest.getContent();
        String projected = task.getSnapshotContent() == null ? "" : task.getSnapshotContent();
        // 终态优先用落库正文；执行中只能用已提交的增量投影，避免读到未发布内容。
        String content = terminal && !saved.isBlank() ? saved : projected;

        AgentTaskMessageVO message = new AgentTaskMessageVO(
                task.getMessageId(),
                "assistant",
                content,
                terminal ? task.getStatus() : AgentChatTask.SNAPSHOT_STATUS_STREAMING,
                task.snapshotRevisionOrZero(),
                latest == null ? null : latest.getSequenceNo());

        return new AgentTaskSnapshotVO(
                taskId,
                task.getConversationId(),
                task.getStatus(),
                task.getStreamVersion(),
                task.snapshotRevisionOrZero(),
                task.getSnapshotEventId(),
                List.of(message),
                task.getUpdatedAt());
    }

    @Override
    public AgentTaskSummaryVO activeTask(String conversationId, AgentCaller caller) {
        AgentChatTask task = taskMapper.selectLatestByConversation(conversationId, caller.userId(), caller.tenantId());
        // 无任务时返回空对象而非 null：前端统一按 taskId 判空，不必区分两种空值。
        return task == null ? new AgentTaskSummaryVO() : AgentTaskSummaryVO.from(task);
    }

    @Override
    public AgentCacheScopeVO cacheScope(AgentCaller caller) {
        return AgentCacheScopeVO.of(caller.tenantId(), caller.userId());
    }

    @Override
    public AgentChatTask requireOwned(String taskId, AgentCaller caller) {
        AgentChatTask task = taskMapper.selectOwned(taskId, caller.userId(), caller.tenantId());
        // 跨账号与不存在返回同一错误，避免通过状态码探测任务是否存在。
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或无权访问");
        }
        return task;
    }

    /**
     * 服务进程异常重启后的遗留任务收敛为失败，避免客户端无限等待。
     * <p>
     * 条件更新保证多实例并发清理时只有一个实例命中并补发终态事件。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void failStaleTasks() {
        for (String taskId : taskMapper.selectStaleTaskIds()) {
            if (taskMapper.markStaleFailed(taskId) != 1) {
                continue;
            }
            try {
                eventLog.append(taskId, "task_failed", Map.of(
                        "schemaVersion", 1,
                        "taskId", taskId,
                        "streamVersion", STREAM_VERSION_PREFIX + taskId,
                        "status", AgentChatTask.STATUS_FAILED));
                eventLog.expireCompleted(taskId);
            } catch (Exception error) {
                log.warn("[AgentTask] 遗留任务终态事件写入失败，由快照兜底: taskId={}", taskId, error);
            }
        }
    }

    /** 复用现有 Agent 编排器；没有绑定浏览器连接，网络中断不会取消任务。 */
    private void runTask(AgentChatRequest chatRequest) {
        String taskId = chatRequest.getTaskId();
        try {
            taskMapper.markRunning(taskId);
            eventLog.append(taskId, "task_started", Map.of(
                    "taskId", taskId,
                    "conversationId", chatRequest.getConversationId(),
                    "streamVersion", STREAM_VERSION_PREFIX + taskId,
                    "schemaVersion", 1,
                    "status", AgentChatTask.STATUS_RUNNING));
            orchestrator.handle(chatRequest, null);
            String messageStatus = messageMapper.selectLatestAssistantStatusByTraceId(taskId);
            finish(taskId, ConversationMessage.STATUS_COMPLETED.equals(messageStatus)
                    ? AgentChatTask.STATUS_COMPLETED : AgentChatTask.STATUS_FAILED);
        } catch (Exception error) {
            log.error("[AgentTask] 执行失败: taskId={}", taskId, error);
            finish(taskId, AgentChatTask.STATUS_FAILED);
        }
    }

    /** 先提交 MySQL 终态，再发 Redis 终态事件；快照可兜底恢复终态。 */
    private void finish(String taskId, String status) {
        taskMapper.markFinished(taskId, status);
        try {
            eventLog.append(taskId, AgentChatTask.STATUS_COMPLETED.equals(status) ? "task_completed" : "task_failed",
                    Map.of("schemaVersion", 1,
                            "taskId", taskId,
                            "streamVersion", STREAM_VERSION_PREFIX + taskId,
                            "status", status));
            eventLog.expireCompleted(taskId);
        } catch (Exception error) {
            log.error("[AgentTask] 终态事件不可用，客户端须读取 MySQL 快照: taskId={}", taskId, error);
        }
    }

    /** 唯一键冲突后回查既有任务；内容摘要不一致说明 requestId 被挪作他用。 */
    private AgentTaskReceiptVO reuseExistingTask(AgentTaskCreateRequest request, AgentCaller caller, String hash) {
        AgentChatTask existing = taskMapper.selectByRequestId(caller.tenantId(), caller.userId(), request.getRequestId());
        if (existing == null || !hash.equals(existing.getRequestHash())) {
            throw new IllegalArgumentException("requestId 已用于不同的请求");
        }
        return AgentTaskReceiptVO.from(existing);
    }

    /** 调度失败时覆盖排队确认缓存；缓存本身不可用时只记日志，不影响失败语义。 */
    private void cacheFailedReceipt(String taskId, AgentTaskReceiptVO receipt, RuntimeException failure) {
        try {
            eventLog.cacheReceipt(taskId, receiptFields(new AgentTaskReceiptVO(receipt.getTaskId(),
                    receipt.getConversationId(), receipt.getMessageId(), AgentChatTask.STATUS_FAILED,
                    receipt.getStreamVersion())));
        } catch (RuntimeException cacheFailure) {
            log.warn("[AgentTask] 失败确认缓存不可用: taskId={}", taskId, cacheFailure);
        }
        log.warn("[AgentTask] 任务调度失败: taskId={}, cause={}", taskId, failure.toString());
    }

    /** 缓存载荷保持与对外回执同构，便于网络结果不明时直接查证。 */
    private Map<String, Object> receiptFields(AgentTaskReceiptVO receipt) {
        return Map.of("taskId", receipt.getTaskId(),
                "conversationId", receipt.getConversationId(),
                "messageId", receipt.getMessageId(),
                "status", receipt.getStatus(),
                "streamVersion", receipt.getStreamVersion());
    }

    /** 对外请求 DTO → 编排器内部入参；服务端字段留空，由调用方在落库后注入。 */
    private AgentChatRequest toChatRequest(AgentTaskCreateRequest request, Long userId) {
        AgentChatRequest chatRequest = new AgentChatRequest();
        chatRequest.setUserId(userId);
        chatRequest.setRequestId(request.getRequestId());
        chatRequest.setMessage(request.getMessage());
        chatRequest.setConversationId(request.getConversationId());
        chatRequest.setFileIds(request.getFileIds());
        chatRequest.setStudyContext(request.getStudyContext());
        chatRequest.setKnowledgeBaseIds(request.getKnowledgeBaseIds());
        chatRequest.setRagRetrievalMode(request.getRagRetrievalMode());
        chatRequest.setForceMode(request.getForceMode());
        chatRequest.setClarificationAnswerJson(request.getClarificationAnswerJson());
        return chatRequest;
    }

    /**
     * 服务层兜底校验。
     * <p>
     * Controller 已有 Bean Validation，这里保留同样的判断，防止服务被其他入口
     * （定时任务、消息消费）直接调用时绕过约束。
     */
    private void validate(AgentTaskCreateRequest request) {
        if (request == null || isBlank(request.getMessage()) || isBlank(request.getRequestId())
                || request.getRequestId().length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("消息和 requestId 不能为空");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception error) {
            throw new IllegalStateException("无法计算请求摘要", error);
        }
    }
}
