package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.repository.AgentTaskRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存任务仓储：语义与 MySQL 实现保持一致，不依赖数据库。
 */
public class InMemoryAgentTaskRepository implements AgentTaskRepository {

    private static final long UNBOUND_STALE_MS = 30_000L;

    private final Map<String, AgentTaskMeta> tasks = new ConcurrentHashMap<>();

    private final Map<String, String> clientIndex = new ConcurrentHashMap<>();

    private final Map<String, String> conversationLatest = new ConcurrentHashMap<>();

    @Override
    public synchronized Reservation reserve(AgentTaskMeta candidate) {
        String key = clientKey(candidate.getUserId(), candidate.getClientRequestId());
        if (key != null) {
            String existingId = clientIndex.get(key);
            AgentTaskMeta existing = existingId == null ? null : tasks.get(existingId);
            if (existing != null) {
                if (existing.getAssistantMessageId() != null || existing.statusEnum().isTerminal()) {
                    return new Reservation(copy(existing), false);
                }
                if (System.currentTimeMillis() - existing.getUpdatedAt() < UNBOUND_STALE_MS) {
                    return new Reservation(copy(existing), false);
                }
                tasks.remove(existing.getTaskId());
                clientIndex.remove(key);
            }
        }
        long now = System.currentTimeMillis();
        AgentTaskMeta stored = copy(candidate);
        stored.setCreatedAt(now);
        stored.setUpdatedAt(now);
        stored.setStatus(AgentTaskStatus.CREATED.name());
        stored.setLastSeq(0L);
        tasks.put(stored.getTaskId(), stored);
        if (key != null) {
            clientIndex.put(key, stored.getTaskId());
        }
        if (stored.getConversationId() != null) {
            conversationLatest.put(stored.getConversationId(), stored.getTaskId());
        }
        return new Reservation(copy(stored), true);
    }

    @Override
    public AgentTaskMeta find(String taskId) {
        AgentTaskMeta meta = taskId == null ? null : tasks.get(taskId);
        return meta == null ? null : copy(meta);
    }

    @Override
    public AgentTaskMeta findByClientRequest(Long userId, String clientRequestId) {
        String taskId = clientIndex.get(clientKey(userId, clientRequestId));
        return taskId == null ? null : find(taskId);
    }

    @Override
    public AgentTaskMeta findLatestByConversation(String conversationId) {
        String taskId = conversationId == null ? null : conversationLatest.get(conversationId);
        return taskId == null ? null : find(taskId);
    }

    @Override
    public synchronized void bindTurn(String taskId, String conversationId, String traceId,
                                      Long userMessageId, Long assistantMessageId,
                                      Integer assistantSequenceNo, String title) {
        AgentTaskMeta meta = tasks.get(taskId);
        if (meta == null) {
            return;
        }
        meta.setConversationId(conversationId);
        meta.setTraceId(traceId);
        meta.setUserMessageId(userMessageId);
        meta.setAssistantMessageId(assistantMessageId);
        meta.setAssistantSequenceNo(assistantSequenceNo);
        meta.setTitle(title);
        meta.setUpdatedAt(System.currentTimeMillis());
        if (conversationId != null) {
            conversationLatest.put(conversationId, taskId);
        }
    }

    @Override
    public synchronized void markRunning(String taskId) {
        AgentTaskMeta meta = tasks.get(taskId);
        if (meta != null && AgentTaskStatus.CREATED.name().equals(meta.getStatus())) {
            meta.setStatus(AgentTaskStatus.RUNNING.name());
            meta.setUpdatedAt(System.currentTimeMillis());
        }
    }

    @Override
    public synchronized void updateTerminalState(String taskId, AgentTaskStatus status, long lastSeq,
                                                 String errorMessage, Long assistantMessageId,
                                                 boolean replayable) {
        AgentTaskMeta meta = tasks.get(taskId);
        if (meta == null) {
            return;
        }
        long now = System.currentTimeMillis();
        meta.setStatus(status.name());
        meta.setLastSeq(Math.max(meta.getLastSeq(), lastSeq));
        meta.setErrorMessage(errorMessage);
        if (assistantMessageId != null) {
            meta.setAssistantMessageId(assistantMessageId);
        }
        meta.setReplayable(meta.isReplayable() && replayable);
        meta.setUpdatedAt(now);
        meta.setFinishedAt(now);
    }

    @Override
    public synchronized void markReplayDegraded(String taskId) {
        AgentTaskMeta meta = tasks.get(taskId);
        if (meta != null) {
            meta.setReplayable(false);
        }
    }

    @Override
    public synchronized void discardUnbound(String taskId) {
        AgentTaskMeta meta = tasks.get(taskId);
        if (meta == null) {
            return;
        }
        if (meta.getAssistantMessageId() == null) {
            tasks.remove(taskId);
            clientIndex.values().removeIf(taskId::equals);
        }
    }

    @Override
    public synchronized List<AgentTaskMeta> findStaleReservations() {
        long threshold = System.currentTimeMillis() - UNBOUND_STALE_MS;
        List<AgentTaskMeta> stale = new ArrayList<>();
        for (AgentTaskMeta meta : tasks.values()) {
            if (meta.getAssistantMessageId() == null
                    && !meta.statusEnum().isTerminal()
                    && meta.getUpdatedAt() < threshold) {
                stale.add(copy(meta));
            }
        }
        return stale;
    }

    private String clientKey(Long userId, String clientRequestId) {
        if (userId == null || clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return userId + ":" + clientRequestId;
    }

    private AgentTaskMeta copy(AgentTaskMeta source) {
        return AgentTaskMeta.builder()
                .taskId(source.getTaskId())
                .conversationId(source.getConversationId())
                .userId(source.getUserId())
                .traceId(source.getTraceId())
                .clientRequestId(source.getClientRequestId())
                .status(source.getStatus())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .finishedAt(source.getFinishedAt())
                .lastSeq(source.getLastSeq())
                .userMessageId(source.getUserMessageId())
                .assistantMessageId(source.getAssistantMessageId())
                .assistantSequenceNo(source.getAssistantSequenceNo())
                .title(source.getTitle())
                .errorMessage(source.getErrorMessage())
                .replayable(source.isReplayable())
                .build();
    }
}