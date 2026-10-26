package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.repository.impl.AgentChatEventLog;
import com.smartorder.edu.agent.service.AgentChatTaskService;
import com.smartorder.edu.agent.service.AgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证任务幂等创建、租户隔离以及快照正文与游标配对。 */
class AgentChatTaskServiceTest {
    @Test
    void createsOneTaskAndReturnsSameTaskForDuplicateRequest() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentChatEventLog log = mock(AgentChatEventLog.class);
        ExecutorService executor = mock(ExecutorService.class);
        AgentChatTaskService service = new AgentChatTaskServiceImpl(jdbc, orchestrator, log, executor);
        AgentChatRequest request = new AgentChatRequest();
        request.setRequestId("request-1");
        request.setMessage("解释线程池");
        request.setConversationId("conversation-1");
        request.setUserId(7L);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> created = service.create(request, 7L, "tenant-1");
        doThrow(new DuplicateKeyException("duplicate")).when(jdbc).update(anyString(), any(Object[].class));
        when(jdbc.queryForList(anyString(), eq("tenant-1"), eq(7L), eq("request-1")))
                .thenReturn(List.of(Map.of("task_id", created.get("taskId"),
                        "conversation_id", "conversation-1", "request_hash", hash,
                        "status", "RUNNING", "stream_version", created.get("streamVersion"))));
        AgentChatRequest retry = new AgentChatRequest();
        retry.setRequestId("request-1");
        retry.setMessage("解释线程池");
        retry.setConversationId("conversation-1");
        Map<String, Object> duplicated = service.create(retry, 7L, "tenant-1");

        assertEquals(created.get("taskId"), duplicated.get("taskId"));
        verify(executor, times(1)).execute(any(Runnable.class));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void rejectsOtherTenantAndKeepsSnapshotCursorWithProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentChatTaskService service = new AgentChatTaskServiceImpl(jdbc,
                mock(AgentOrchestrator.class), mock(AgentChatEventLog.class), mock(ExecutorService.class));
        when(jdbc.queryForList(anyString(), eq("task-1"), eq(7L), eq("tenant-b")))
                .thenReturn(List.of());
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> service.owned("task-1", 7L, "tenant-b"));
        assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatusCode());

        when(jdbc.queryForList(anyString(), eq("task-1"), eq(7L), eq("tenant-a")))
                .thenReturn(List.of(Map.of("task_id", "task-1", "conversation_id", "conv-1",
                        "status", "RUNNING", "stream_version", "v1-task-1",
                        "snapshot_revision", 5L, "snapshot_event_id", "1-5",
                        "snapshot_content", "12345", "updated_at", "now")));
        when(jdbc.queryForList(anyString(), eq("task-1"), eq(7L)))
                .thenReturn(List.of(Map.of("id", 9L, "role", "assistant",
                        "status", "RUNNING", "content", "")));
        Map<String, Object> snapshot = service.snapshot("task-1", 7L, "tenant-a");
        assertEquals("1-5", snapshot.get("snapshotEventId"));
        assertEquals("12345", ((List<Map<String, Object>>) snapshot.get("messages")).get(0).get("content"));
    }

    /** F5 恰逢任务完成时仍能发现终态任务，不让页面永久停留在生成中。 */
    @Test
    void discoversRecentlyCompletedTaskWithoutStartingWorker() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExecutorService executor = mock(ExecutorService.class);
        AgentChatTaskService service = new AgentChatTaskServiceImpl(jdbc,
                mock(AgentOrchestrator.class), mock(AgentChatEventLog.class), executor);
        when(jdbc.queryForList(anyString(), eq("conv-1"), eq(7L), eq("tenant-a")))
                .thenReturn(List.of(Map.of("task_id", "task-1", "conversation_id", "conv-1",
                        "status", "COMPLETED", "stream_version", "v1-task-1")));

        Map<String, Object> recent = service.activeForConversation("conv-1", 7L, "tenant-a");

        assertEquals("COMPLETED", recent.get("status"));
        verify(executor, never()).execute(any(Runnable.class));
    }
}
