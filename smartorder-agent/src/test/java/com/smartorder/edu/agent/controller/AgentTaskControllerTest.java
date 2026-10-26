package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.runtime.AgentTaskSubscriptionPlan;
import com.smartorder.edu.agent.service.AgentChatService;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订阅接口的关键约束：校验失败必须还能返回真实 HTTP 状态码；
 * 校验通过后先返回 emitter，再提交异步 attach。
 */
class AgentTaskControllerTest {

    private final AgentChatService service = mock(AgentChatService.class);

    private final AgentTaskController controller = new AgentTaskController(service);

    @AfterEach
    void tearDown() {
        ThreadLocalUtils.remove();
    }

    @Test
    void shouldReturnRealHttpStatusWhenValidationFailsBeforeStreamStarts() {
        login(7L);
        when(service.validateSubscription(eq("t-1"), eq(7L), eq(0L), any()))
                .thenThrow(AgentTaskException.cursorExpired(0));

        ResponseEntity<SseEmitter> response = controller.subscribe("t-1", 0L, null);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        verify(service, never()).attachAsync(any());
    }

    @Test
    void shouldReturnEmitterThenScheduleAttach() {
        login(7L);
        AgentTaskSubscriptionPlan plan = new AgentTaskSubscriptionPlan(null, null, null);
        when(service.validateSubscription(eq("t-1"), eq(7L), eq(0L), any())).thenReturn(plan);

        ResponseEntity<SseEmitter> response = controller.subscribe("t-1", 0L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        InOrder order = inOrder(service);
        order.verify(service).validateSubscription(eq("t-1"), eq(7L), eq(0L), any());
        order.verify(service).attachAsync(plan);
    }

    @Test
    void shouldReturn503WhenAttachCannotBeScheduled() {
        login(7L);
        AgentTaskSubscriptionPlan plan = new AgentTaskSubscriptionPlan(null, null, null);
        when(service.validateSubscription(eq("t-1"), eq(7L), eq(0L), any())).thenReturn(plan);
        doThrow(new RejectedExecutionException("full")).when(service).attachAsync(any());

        ResponseEntity<SseEmitter> response = controller.subscribe("t-1", 0L, null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    private void login(long userId) {
        Claims claims = mock(Claims.class);
        when(claims.get("userId", Long.class)).thenReturn(userId);
        ThreadLocalUtils.set(claims);
    }
}
