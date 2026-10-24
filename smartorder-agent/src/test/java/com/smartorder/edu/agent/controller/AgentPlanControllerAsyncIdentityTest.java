package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.clarification.ClarificationService;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.dto.ClarificationAnswerRequest;
import com.smartorder.edu.agent.model.dto.GateConfirmRequest;
import com.smartorder.edu.agent.model.dto.PlanConfirmRequest;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.orchestrator.PlanAndExecuteEngine;
import com.smartorder.edu.agent.plan.PlanConfirmService;
import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.service.AgentRunStateService;
import com.smartorder.edu.agent.service.ConversationService;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证异步规划入口使用请求线程已认证的用户身份校验会话。 */
class AgentPlanControllerAsyncIdentityTest {

    private static final Long USER_ID = 2053685915284951040L;
    private static final String CONVERSATION_ID = "28c0b424-c357-4fb7-939a-04176e205425";
    private final ConversationService conversationService = mock(ConversationService.class);
    private final PlanAndExecuteEngine engine = mock(PlanAndExecuteEngine.class);
    private final PlanDraftService draftService = mock(PlanDraftService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final AgentPlanController controller = new AgentPlanController(
            mock(PlanConfirmService.class), draftService, engine, mock(ClarificationService.class),
            mock(AgentTraceEventMapper.class), mock(TraceEventDispatcher.class), executor,
            mock(AgentRunStateService.class), mock(AgentPlanDraftMapper.class), conversationService);

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtils.remove();
    }

    @Test
    void clarificationResumeChecksConversationWithAuthenticatedUserOnWorker() {
        authenticateRequestAndRunWithoutThreadLocal();
        ClarificationAnswerRequest request = ClarificationAnswerRequest.builder()
                .answer("java_backend").displayAnswer("Java 后端八股")
                .conversationId(CONVERSATION_ID).build();

        controller.answerClarification("draft-1", "clar-1", request);

        verify(conversationService).assertActive(CONVERSATION_ID, USER_ID);
        verify(engine).resumeAfterClarification(eq("draft-1"), eq("clar-1"), eq("java_backend"),
                eq("Java 后端八股"), eq(USER_ID), eq(CONVERSATION_ID), any(), anyString());
    }

    @Test
    void confirmationGateChecksConversationWithAuthenticatedUserOnWorker() {
        authenticateRequestAndRunWithoutThreadLocal();
        GateConfirmRequest request = new GateConfirmRequest();
        request.setConversationId(CONVERSATION_ID);

        controller.confirmGate("draft-1", request);

        verify(conversationService).assertActive(CONVERSATION_ID, USER_ID);
        verify(engine).resumeAfterConfirmation(eq("draft-1"), eq(USER_ID), eq(CONVERSATION_ID),
                any(), anyString());
    }

    @Test
    void planConfirmationChecksConversationWithAuthenticatedUserOnWorker() {
        authenticateRequestAndRunWithoutThreadLocal();
        PlanConfirmRequest request = new PlanConfirmRequest();
        request.setDraftId("draft-1");
        request.setConversationId(CONVERSATION_ID);
        AgentPlanDraft draft = new AgentPlanDraft();
        draft.setPlanType("EXECUTION_PLAN");
        when(draftService.getByDraftId("draft-1")).thenReturn(draft);

        controller.confirmPlan(request);

        verify(conversationService).assertActive(CONVERSATION_ID, USER_ID);
        verify(engine).executeExecutionPlan(eq("draft-1"), eq(USER_ID), eq(CONVERSATION_ID),
                any(), anyString());
    }

    private void authenticateRequestAndRunWithoutThreadLocal() {
        Claims claims = mock(Claims.class);
        when(claims.get("userId", Long.class)).thenReturn(USER_ID);
        ThreadLocalUtils.set(claims);
        // 模拟请求线程提交任务后，线程池工作线程没有 JWT ThreadLocal。
        doAnswer(invocation -> {
            ThreadLocalUtils.remove();
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }
}
