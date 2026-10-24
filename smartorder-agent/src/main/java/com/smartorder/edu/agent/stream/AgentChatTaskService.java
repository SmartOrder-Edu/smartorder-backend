package com.smartorder.edu.agent.stream;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.orchestrator.AgentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 任务元数据与 Agent 执行生命周期；读取快照和 SSE 订阅绝不调用本服务的 create。 */
@Slf4j
@Service
public class AgentChatTaskService {
    private final JdbcTemplate jdbc;
    private final AgentOrchestrator orchestrator;
    private final AgentEventLog eventLog;
    private final ExecutorService executor;

    public AgentChatTaskService(JdbcTemplate jdbc, AgentOrchestrator orchestrator, AgentEventLog eventLog,
                                @Qualifier("agentTaskExecutor") ExecutorService executor) {
        this.jdbc = jdbc;
        this.orchestrator = orchestrator;
        this.eventLog = eventLog;
        this.executor = executor;
    }

    /** 唯一 (tenantId,userId,requestId) 在数据库中仲裁重复 POST，重复命中不重新调度。 */
    public Map<String, Object> create(AgentChatRequest request, Long userId, String tenantId) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()
                || request.getRequestId() == null || request.getRequestId().isBlank()
                || request.getRequestId().length() > 100) {
            throw new IllegalArgumentException("消息和 requestId 不能为空");
        }
        request.setUserId(userId);
        request.setTaskId(null);
        request.setMessageId(null);
        String originalConversationId = request.getConversationId();
        String hash = digest(JSON.toJSONString(request));
        String taskId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String conversationId = originalConversationId == null || originalConversationId.isBlank()
                ? UUID.randomUUID().toString() : originalConversationId;
        String version = "v1-" + taskId;
        try {
            jdbc.update("INSERT INTO agent_chat_task(task_id,message_id,conversation_id,user_id,tenant_id,request_id,request_hash,status,stream_version) "
                            + "VALUES(?,?,?,?,?,?,?,'QUEUED',?)",
                    taskId, messageId, conversationId, userId, tenantId, request.getRequestId(), hash, version);
        } catch (DuplicateKeyException duplicate) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM agent_chat_task WHERE tenant_id=? AND user_id=? AND request_id=?",
                    tenantId, userId, request.getRequestId());
            if (rows.isEmpty() || !hash.equals(rows.get(0).get("request_hash"))) {
                throw new IllegalArgumentException("requestId 已用于不同的请求");
            }
            return describe(rows.get(0));
        }
        request.setConversationId(conversationId);
        request.setTaskId(taskId);
        request.setMessageId(messageId);
        Map<String, Object> receipt = Map.of("taskId", taskId, "conversationId", conversationId,
                "messageId", messageId, "status", "QUEUED", "streamVersion", version);
        try {
            // 先缓存确认标识，再调度后台 Agent；HTTP 响应无需等待首个模型事件。
            eventLog.cacheReceipt(taskId, receipt);
            executor.execute(() -> runTask(request));
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE agent_chat_task SET status='FAILED', finished_at=NOW(3) WHERE task_id=?", taskId);
            // 调度失败时覆盖排队确认缓存，避免客户端读取到永远不会执行的任务。
            try {
                eventLog.cacheReceipt(taskId, Map.of("taskId", taskId, "conversationId", conversationId,
                        "messageId", messageId, "status", "FAILED", "streamVersion", version));
            } catch (RuntimeException cacheFailure) {
                log.warn("[AgentTask] 失败确认缓存不可用: taskId={}", taskId, cacheFailure);
            }
            if (failure instanceof RejectedExecutionException) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 任务队列已满", failure);
            }
            throw failure;
        }
        return receipt;
    }

    /** 复用现有 Agent 编排器；没有绑定浏览器连接，网络中断不会取消任务。 */
    private void runTask(AgentChatRequest request) {
        String taskId = request.getTaskId();
        try {
            jdbc.update("UPDATE agent_chat_task SET status='RUNNING' WHERE task_id=?", taskId);
            eventLog.append(taskId, "task_started", Map.of("taskId", taskId,
                    "conversationId", request.getConversationId(), "streamVersion", "v1-" + taskId,
                    "schemaVersion", 1, "status", "RUNNING"));
            orchestrator.handle(request, null);
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM conversation_message WHERE trace_id=? AND role='assistant' "
                            + "ORDER BY id DESC LIMIT 1", String.class, taskId);
            String status = statuses.isEmpty() || !"COMPLETED".equals(statuses.get(0)) ? "FAILED" : "COMPLETED";
            finish(taskId, status);
        } catch (Exception error) {
            log.error("[AgentTask] 执行失败: taskId={}", taskId, error);
            finish(taskId, "FAILED");
        }
    }

    /** 先提交 MySQL 终态，再发 Redis 终态事件；快照可兜底恢复终态。 */
    private void finish(String taskId, String status) {
        jdbc.update("UPDATE agent_chat_task SET status=?, finished_at=NOW(3) WHERE task_id=?", status, taskId);
        try {
            eventLog.append(taskId, "COMPLETED".equals(status) ? "task_completed" : "task_failed",
                    Map.of("schemaVersion", 1, "taskId", taskId, "streamVersion", "v1-" + taskId,
                            "status", status));
            eventLog.expireCompleted(taskId);
        } catch (Exception error) {
            log.error("[AgentTask] 终态事件不可用，客户端须读取 MySQL 快照: taskId={}", taskId, error);
        }
    }

    /** 按任务所有者和租户校验，跨账号返回同一不可访问错误。 */
    public Map<String, Object> owned(String taskId, Long userId, String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM agent_chat_task WHERE task_id=? AND user_id=? AND tenant_id=?",
                taskId, userId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或无权访问");
        return rows.get(0);
    }

    /** 从同一任务行读取内容和游标，保证快照不会超前于已保存文本。 */
    public Map<String, Object> snapshot(String taskId, Long userId, String tenantId) {
        Map<String, Object> task = owned(taskId, userId, tenantId);
        boolean terminal = isTerminal(String.valueOf(task.get("status")));
        List<Map<String, Object>> messages = jdbc.queryForList(
                "SELECT id, sequence_no, role, content, status FROM conversation_message WHERE trace_id=? AND user_id=? "
                        + "AND role='assistant' AND is_deleted=0 ORDER BY id", taskId, userId);
        String messageId = String.valueOf(task.get("message_id"));
        String saved = messages.isEmpty() || messages.get(messages.size() - 1).get("content") == null
                ? "" : String.valueOf(messages.get(messages.size() - 1).get("content"));
        String content = terminal && !saved.isBlank()
                ? saved : String.valueOf(task.getOrDefault("snapshot_content", ""));
        if ("null".equals(content)) content = "";
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", messageId);
        message.put("role", "assistant");
        message.put("content", content);
        message.put("status", terminal ? String.valueOf(task.get("status")) : "STREAMING");
        message.put("revision", task.get("snapshot_revision"));
        message.put("sequenceNo", messages.isEmpty() ? null : messages.get(messages.size() - 1).get("sequence_no"));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", taskId);
        snapshot.put("conversationId", task.get("conversation_id"));
        snapshot.put("status", task.get("status"));
        snapshot.put("streamVersion", task.get("stream_version"));
        snapshot.put("snapshotRevision", task.get("snapshot_revision"));
        snapshot.put("snapshotEventId", task.get("snapshot_event_id"));
        snapshot.put("messages", List.of(message));
        snapshot.put("updatedAt", task.get("updated_at"));
        return snapshot;
    }

    /** 返回最近任务的终态或运行态，避免历史读取与任务完成之间的竞态。 */
    public Map<String, Object> activeForConversation(String conversationId, Long userId, String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM agent_chat_task WHERE conversation_id=? AND user_id=? AND tenant_id=? "
                        + "ORDER BY created_at DESC LIMIT 1",
                conversationId, userId, tenantId);
        return rows.isEmpty() ? Map.of() : describe(rows.get(0));
    }

    /** 终态统一判断，避免 SSE 读取线程在完成后无限阻塞。 */
    public boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /** 服务进程异常重启后的遗留任务收敛为失败，避免客户端无限等待。 */
    @Scheduled(fixedDelay = 60_000L)
    public void failStaleTasks() {
        List<String> stale = jdbc.queryForList(
                "SELECT task_id FROM agent_chat_task WHERE status IN ('QUEUED','RUNNING') "
                        + "AND updated_at < NOW(3) - INTERVAL 30 MINUTE LIMIT 100", String.class);
        for (String taskId : stale) {
            int updated = jdbc.update("UPDATE agent_chat_task SET status='FAILED', finished_at=NOW(3) "
                    + "WHERE task_id=? AND status IN ('QUEUED','RUNNING') "
                    + "AND updated_at < NOW(3) - INTERVAL 30 MINUTE", taskId);
            if (updated == 1) {
                try {
                    eventLog.append(taskId, "task_failed", Map.of("schemaVersion", 1, "taskId", taskId,
                            "streamVersion", "v1-" + taskId, "status", "FAILED"));
                    eventLog.expireCompleted(taskId);
                } catch (Exception error) {
                    log.warn("[AgentTask] 遗留任务终态事件写入失败，由快照兜底: taskId={}", taskId);
                }
            }
        }
    }

    private Map<String, Object> describe(Map<String, Object> row) {
        return Map.of("taskId", row.get("task_id"), "conversationId", row.get("conversation_id"),
                "messageId", row.get("message_id"), "status", row.get("status"),
                "streamVersion", row.get("stream_version"));
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
