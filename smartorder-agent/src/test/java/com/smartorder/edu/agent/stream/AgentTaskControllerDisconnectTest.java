package com.smartorder.edu.agent.stream;

import com.smartorder.edu.agent.controller.AgentTaskController;
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
    /** 断点请求携带的消息 ID 必须与任务绑定，不能跨消息读取同一任务事件。 */
    @Test
    void rejectsMismatchedMessageId() {
        AgentTaskController controller = new AgentTaskController(mock(AgentChatTaskService.class),
                mock(AgentEventLog.class), Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(
                controller, "validateMessageId", Map.of("message_id", "message-1"), "message-2"));
    }

    @Test
    void disconnectDoesNotCompleteBrokenEmitter() throws IOException {
        AgentChatTaskService tasks = mock(AgentChatTaskService.class);
        AgentEventLog events = mock(AgentEventLog.class);
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
