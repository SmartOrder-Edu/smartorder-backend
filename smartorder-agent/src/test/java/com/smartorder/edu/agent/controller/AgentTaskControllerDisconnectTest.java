package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.controller.AgentTaskController;
import com.smartorder.edu.agent.repository.impl.AgentChatEventLog;
import com.smartorder.edu.agent.service.AgentChatTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回归验证客户端断开后不二次刷新 SSE，也不把异常转为 JSON 响应。 */
class AgentTaskControllerDisconnectTest {
    @Test
    void disconnectDoesNotCompleteBrokenEmitter() throws IOException {
        AgentChatTaskService tasks = mock(AgentChatTaskService.class);
        AgentChatEventLog events = mock(AgentChatEventLog.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(events.read("task-1", "0-0")).thenReturn(List.of());
        when(tasks.owned("task-1", 7L, "tenant-a")).thenReturn(Map.of(
                "status", "RUNNING", "event_count", 0L));
        doThrow(new IOException("client disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        AgentTaskController controller = new AgentTaskController(tasks, events, Runnable::run);

        ReflectionTestUtils.invokeMethod(controller, "readLoop", emitter,
                "task-1", 7L, "tenant-a", "v1-task-1", "0-0",
                Map.of("stream_version", "v1-task-1", "event_count", 0L));

        verify(emitter, never()).complete();
    }

}
