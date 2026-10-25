package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.mapper.AgentChatTaskMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentTaskCreateRequest;
import com.smartorder.edu.agent.model.entity.AgentChatTask;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.vo.AgentTaskReceiptVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSnapshotVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSummaryVO;
import com.smartorder.edu.agent.orchestrator.AgentOrchestrator;
import com.smartorder.edu.agent.stream.AgentEventLog;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证任务幂等创建、租户隔离以及快照正文与游标配对。 */
class AgentTaskServiceImplTest {

    private static final AgentCaller CALLER = new AgentCaller(7L, "tenant-a");

    private final AgentChatTaskMapper taskMapper = mock(AgentChatTaskMapper.class);
    private final ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final AgentEventLog eventLog = mock(AgentEventLog.class);
    private final ExecutorService executor = mock(ExecutorService.class);

    private AgentTaskServiceImpl service() {
        return new AgentTaskServiceImpl(taskMapper, messageMapper, orchestrator, eventLog, executor);
    }

    /** 任务队列满时请求立即失败，并把预写的 Redis 确认改为失败状态。 */
    @Test
    void rejectsFullQueueWithoutRunningAgent() {
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));

        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRequestId("request-full");
        request.setMessage("测试队列");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().create(request, CALLER));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(eventLog).cacheReceipt(anyString(), argThat(receipt -> "FAILED".equals(receipt.get("status"))));
        verify(taskMapper).markDispatchFailed(anyString());
        verify(orchestrator, never()).handle(any(), any());
    }

    @Test
    void createsOneTaskAndReturnsSameTaskForDuplicateRequest() throws Exception {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRequestId("request-1");
        request.setMessage("解释线程池");
        request.setConversationId("conversation-1");

        AgentTaskReceiptVO created = service().create(request, CALLER);
        assertNotEquals(created.getTaskId(), created.getMessageId(),
                "助手消息 ID 应独立于任务 ID，并在首次确认时固定");
        verify(eventLog).cacheReceipt(eq(created.getTaskId()),
                argThat(receipt -> created.getMessageId().equals(receipt.get("messageId"))));

        // 同一 requestId 的第二次提交命中唯一索引，回查既有任务且不重新调度。
        AgentChatTask existing = new AgentChatTask();
        existing.setTaskId(created.getTaskId());
        existing.setMessageId(created.getMessageId());
        existing.setConversationId("conversation-1");
        existing.setRequestHash(requestHash("request-1", "解释线程池", "conversation-1"));
        existing.setStatus(AgentChatTask.STATUS_RUNNING);
        existing.setStreamVersion(created.getStreamVersion());
        doThrow(new DuplicateKeyException("duplicate")).when(taskMapper).insert(any(AgentChatTask.class));
        when(taskMapper.selectByRequestId("tenant-a", 7L, "request-1")).thenReturn(existing);

        AgentTaskReceiptVO duplicated = service().create(request, CALLER);

        assertEquals(created.getTaskId(), duplicated.getTaskId());
        assertEquals(created.getMessageId(), duplicated.getMessageId());
        verify(executor, times(1)).execute(any(Runnable.class));
        verifyNoInteractions(orchestrator);
    }

    /** 同一 requestId 换了内容必须拒绝，否则会静默返回上一轮回答。 */
    @Test
    void rejectsRequestIdReusedForDifferentContent() {
        AgentChatTask existing = new AgentChatTask();
        existing.setTaskId("task-1");
        existing.setRequestHash("another-hash");
        doThrow(new DuplicateKeyException("duplicate")).when(taskMapper).insert(any(AgentChatTask.class));
        when(taskMapper.selectByRequestId("tenant-a", 7L, "request-1")).thenReturn(existing);

        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRequestId("request-1");
        request.setMessage("换一个问题");

        assertThrows(IllegalArgumentException.class, () -> service().create(request, CALLER));
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void rejectsOtherTenantAndKeepsSnapshotCursorWithProjection() {
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> service().requireOwned("task-1", new AgentCaller(7L, "tenant-b")));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        AgentChatTask task = new AgentChatTask();
        task.setTaskId("task-1");
        task.setMessageId("message-1");
        task.setConversationId("conv-1");
        task.setStatus(AgentChatTask.STATUS_RUNNING);
        task.setStreamVersion("v1-task-1");
        task.setSnapshotRevision(5L);
        task.setSnapshotEventId("1-5");
        task.setSnapshotContent("12345");
        when(taskMapper.selectOwned("task-1", 7L, "tenant-a")).thenReturn(task);
        ConversationMessage message = new ConversationMessage();
        message.setId(9L);
        message.setRole("assistant");
        message.setStatus(ConversationMessage.STATUS_RUNNING);
        message.setContent("");
        when(messageMapper.selectAssistantByTraceId("task-1", 7L)).thenReturn(List.of(message));

        AgentTaskSnapshotVO snapshot = service().snapshot("task-1", CALLER);

        assertEquals("1-5", snapshot.getSnapshotEventId());
        assertEquals("message-1", snapshot.getMessages().get(0).getMessageId());
        assertEquals("12345", snapshot.getMessages().get(0).getContent());
        assertEquals(AgentChatTask.SNAPSHOT_STATUS_STREAMING, snapshot.getMessages().get(0).getStatus());
    }

    /** F5 恰逢任务完成时仍能发现终态任务，不让页面永久停留在生成中。 */
    @Test
    void discoversRecentlyCompletedTaskWithoutStartingWorker() {
        AgentChatTask task = new AgentChatTask();
        task.setTaskId("task-1");
        task.setMessageId("message-1");
        task.setConversationId("conv-1");
        task.setStatus(AgentChatTask.STATUS_COMPLETED);
        task.setStreamVersion("v1-task-1");
        when(taskMapper.selectLatestByConversation("conv-1", 7L, "tenant-a")).thenReturn(task);

        AgentTaskSummaryVO recent = service().activeTask("conv-1", CALLER);

        assertEquals(AgentChatTask.STATUS_COMPLETED, recent.getStatus());
        verify(executor, never()).execute(any(Runnable.class));
        // 没有任务时返回空对象而不是 null，前端统一按 taskId 判空。
        assertNotNull(service().activeTask("conv-2", CALLER));
        assertNull(service().activeTask("conv-2", CALLER).getTaskId());
    }

    /** 摘要基于编排器实际收到的内部请求体计算，含 userId 与 requestId。 */
    private String requestHash(String requestId, String message, String conversationId) throws Exception {
        AgentChatRequest internal = new AgentChatRequest();
        internal.setUserId(7L);
        internal.setRequestId(requestId);
        internal.setMessage(message);
        internal.setConversationId(conversationId);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJSONString(internal).getBytes(StandardCharsets.UTF_8)));
    }
}
