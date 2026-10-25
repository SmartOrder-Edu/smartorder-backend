package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.model.dto.AgentTaskSubscribeQuery;
import com.smartorder.edu.agent.model.entity.AgentChatTask;
import com.smartorder.edu.agent.service.AgentTaskService;
import com.smartorder.edu.agent.stream.AgentEventLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回归验证订阅鉴权与断连处理：断连不得二次刷新 SSE，也不能把异常转成 JSON 响应。 */
class AgentTaskStreamServiceImplTest {

    private static final AgentCaller CALLER = new AgentCaller(7L, "tenant-a");

    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final AgentEventLog events = mock(AgentEventLog.class);

    private AgentTaskStreamServiceImpl service() {
        return new AgentTaskStreamServiceImpl(taskService, events, Runnable::run);
    }

    /** 断点请求携带的消息 ID 必须与任务绑定，不能跨消息读取同一任务事件。 */
    @Test
    void rejectsMismatchedMessageId() {
        AgentChatTask task = runningTask();
        when(taskService.requireOwned("task-1", CALLER)).thenReturn(task);
        AgentTaskSubscribeQuery query = query("v1-task-1", "message-2");

        assertThrows(IllegalArgumentException.class,
                () -> service().subscribe("task-1", query, null, CALLER));
    }

    @Test
    void disconnectDoesNotCompleteBrokenEmitter() throws IOException {
        AgentChatTask task = runningTask();
        when(taskService.requireOwned("task-1", CALLER)).thenReturn(task);
        when(events.read("task-1", "0-0")).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("client disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service().readLoop(emitter, "task-1", CALLER, "v1-task-1", "0-0", task);

        verify(emitter, never()).complete();
    }

    private AgentTaskSubscribeQuery query(String streamVersion, String messageId) {
        AgentTaskSubscribeQuery query = new AgentTaskSubscribeQuery();
        query.setStreamVersion(streamVersion);
        query.setMessageId(messageId);
        return query;
    }

    private AgentChatTask runningTask() {
        AgentChatTask task = new AgentChatTask();
        task.setTaskId("task-1");
        task.setMessageId("message-1");
        task.setConversationId("conv-1");
        task.setStatus(AgentChatTask.STATUS_RUNNING);
        task.setStreamVersion("v1-task-1");
        task.setEventCount(0L);
        return task;
    }
}
