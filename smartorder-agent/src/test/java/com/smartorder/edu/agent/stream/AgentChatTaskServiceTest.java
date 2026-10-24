package com.smartorder.edu.agent.stream;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.orchestrator.AgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证任务幂等创建、租户隔离以及快照正文与游标配对。 */
class AgentChatTaskServiceTest {
    /** 任务队列满时请求立即失败，并把预写的 Redis 确认改为失败状态。 */
    @Test
    void rejectsFullQueueWithoutRunningAgent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentEventLog log = mock(AgentEventLog.class);
        ExecutorService executor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));
        AgentChatTaskService service = new AgentChatTaskService(jdbc, orchestrator, log, executor);
        AgentChatRequest request = new AgentChatRequest();
        request.setRequestId("request-full");
        request.setMessage("测试队列");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(request, 7L, "tenant-a"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(log).cacheReceipt(anyString(), argThat(receipt -> "FAILED".equals(receipt.get("status"))));
        verify(orchestrator, never()).handle(any(), any());
    }
    @Test
    void createsOneTaskAndReturnsSameTaskForDuplicateRequest() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentEventLog log = mock(AgentEventLog.class);
        ExecutorService executor = mock(ExecutorService.class);
        AgentChatTaskService service = new AgentChatTaskService(jdbc, orchestrator, log, executor);
        AgentChatRequest request = new AgentChatRequest();
        request.setRequestId("request-1");
        request.setMessage("解释线程池");
        request.setConversationId("conversation-1");
        request.setUserId(7L);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> created = service.create(request, 7L, "tenant-1");
        assertNotEquals(created.get("taskId"), created.get("messageId"),
                "助手消息 ID 应独立于任务 ID，并在首次确认时固定");
        verify(log).cacheReceipt(eq(String.valueOf(created.get("taskId"))),
                argThat(receipt -> created.get("messageId").equals(receipt.get("messageId"))));
        doThrow(new DuplicateKeyException("duplicate")).when(jdbc).update(anyString(), any(Object[].class));
        when(jdbc.queryForList(anyString(), eq("tenant-1"), eq(7L), eq("request-1")))
                .thenReturn(List.of(Map.of("task_id", created.get("taskId"),
                        "message_id", created.get("messageId"),
                        "conversation_id", "conversation-1", "request_hash", hash,
                        "status", "RUNNING", "stream_version", created.get("streamVersion"))));
        AgentChatRequest retry = new AgentChatRequest();
        retry.setRequestId("request-1");
        retry.setMessage("解释线程池");
        retry.setConversationId("conversation-1");
        Map<String, Object> duplicated = service.create(retry, 7L, "tenant-1");

        assertEquals(created.get("taskId"), duplicated.get("taskId"));
        assertEquals(created.get("messageId"), duplicated.get("messageId"));
        verify(executor, times(1)).execute(any(Runnable.class));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void rejectsOtherTenantAndKeepsSnapshotCursorWithProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentChatTaskService service = new AgentChatTaskService(jdbc,
                mock(AgentOrchestrator.class), mock(AgentEventLog.class), mock(ExecutorService.class));
        when(jdbc.queryForList(anyString(), eq("task-1"), eq(7L), eq("tenant-b")))
                .thenReturn(List.of());
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> service.owned("task-1", 7L, "tenant-b"));
        assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatusCode());

        when(jdbc.queryForList(anyString(), eq("task-1"), eq(7L), eq("tenant-a")))
                .thenReturn(List.of(Map.of("task_id", "task-1", "message_id", "message-1", "conversation_id", "conv-1",
                        "status", "RUNNING", "stream_version", "v1-task-1",
                        "snapshot_revision", 5L, "snapshot_event_id", "1-5",
                        "snapshot_content", "12345", "updated_at", "now")));
        when(jdbc.queryForList(anyString(), eq("task-1"), eq(7L)))
                .thenReturn(List.of(Map.of("id", 9L, "role", "assistant",
                        "status", "RUNNING", "content", "")));
        Map<String, Object> snapshot = service.snapshot("task-1", 7L, "tenant-a");
        assertEquals("1-5", snapshot.get("snapshotEventId"));
        assertEquals("message-1", ((List<Map<String, Object>>) snapshot.get("messages")).get(0).get("messageId"));
        assertEquals("12345", ((List<Map<String, Object>>) snapshot.get("messages")).get(0).get("content"));
    }

    /** F5 恰逢任务完成时仍能发现终态任务，不让页面永久停留在生成中。 */
    @Test
    void discoversRecentlyCompletedTaskWithoutStartingWorker() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExecutorService executor = mock(ExecutorService.class);
        AgentChatTaskService service = new AgentChatTaskService(jdbc,
                mock(AgentOrchestrator.class), mock(AgentEventLog.class), executor);
        when(jdbc.queryForList(anyString(), eq("conv-1"), eq(7L), eq("tenant-a")))
                .thenReturn(List.of(Map.of("task_id", "task-1", "message_id", "message-1", "conversation_id", "conv-1",
                        "status", "COMPLETED", "stream_version", "v1-task-1")));

        Map<String, Object> recent = service.activeForConversation("conv-1", 7L, "tenant-a");

        assertEquals("COMPLETED", recent.get("status"));
        verify(executor, never()).execute(any(Runnable.class));
    }
}
