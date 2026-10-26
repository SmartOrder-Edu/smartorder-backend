package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.entity.AgentClarification;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.service.AgentRunStateService;
import com.smartorder.edu.agent.service.ClarificationService;
import com.smartorder.edu.agent.service.ConversationContextService;
import com.smartorder.edu.agent.service.PlanDraftService;
import com.smartorder.edu.agent.service.TraceEventDispatcher;
import com.smartorder.edu.agent.service.impl.LLMStepVerifier;
import com.smartorder.edu.agent.service.impl.PlanSchemaValidator;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolRegistry;
import com.smartorder.edu.agent.util.PromptLoader;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanAndExecuteEngineTest {

    @Test
    void resumeAfterClarificationPersistsStepOutputForPausedTask() {
        String draftId = "draft-1";
        String clarificationId = "clar-1";
        Long userId = 7L;
        String conversationId = "conversation-1";

        ExecutionStep pausedStep = ExecutionStep.builder()
                .stepId("step-1")
                .description("请选择你当前的数据结构基础")
                .requiredTools(List.of("askUser"))
                .gate("CLARIFICATION")
                .status("PAUSED")
                .build();
        ExecutionPlan pausedPlan = ExecutionPlan.builder()
                .userGoal("一个月快速学习数据结构")
                .steps(List.of(pausedStep))
                .status("PAUSED_CLARIFICATION")
                .currentStepIndex(0)
                .build();

        AgentPlanDraft draft = new AgentPlanDraft();
        draft.setDraftId(draftId);
        draft.setUserId(userId);
        draft.setConversationId(conversationId);
        draft.setPlanType("EXECUTION_PLAN");
        draft.setExecutionPlanJson(JSON.toJSONString(pausedPlan));
        AtomicReference<AgentPlanDraft> draftRef = new AtomicReference<>(draft);

        AgentClarification clarification = new AgentClarification();
        clarification.setClarificationId(clarificationId);
        clarification.setDraftId(draftId);
        clarification.setUserId(userId);
        clarification.setTaskId("step-1");
        clarification.setSlotKey("current_level");
        clarification.setQuestion("请选择你当前的数据结构基础");
        clarification.setAnswerType("SINGLE_SELECT");
        clarification.setStatus("ANSWERED");
        clarification.setSlotPriority(1);

        PlanDraftService draftService = mock(PlanDraftService.class);
        when(draftService.getByDraftId(draftId)).thenAnswer(invocation -> draftRef.get());
        doAnswer(invocation -> {
            ExecutionPlan updatedPlan = invocation.getArgument(1);
            draftRef.get().setExecutionPlanJson(JSON.toJSONString(updatedPlan));
            draftRef.get().setResumeStepIndex(updatedPlan.getCurrentStepIndex());
            return null;
        }).when(draftService).updateExecutionPlan(eq(draftId), any(ExecutionPlan.class));

        ClarificationService clarificationService = mock(ClarificationService.class);
        when(clarificationService.submitAnswer(clarificationId, "beginner", draftId, userId))
                .thenReturn(null);
        when(clarificationService.getClarificationsForDraft(draftId))
                .thenReturn(List.of(clarification));

        PlanAndExecuteEngine engine = new PlanAndExecuteEngine(
                mock(ChatModel.class),
                mock(ChatModel.class),
                mock(ToolRegistry.class),
                mock(LangChain4jToolAdapter.class),
                mock(PlanSchemaValidator.class),
                draftService,
                clarificationService,
                mock(TraceEventDispatcher.class),
                mock(ConversationContextService.class),
                mock(SelfManagedReActEngine.class),
                mock(LLMStepVerifier.class),
                mock(PromptLoader.class),
                mock(AgentRunStateService.class)
        );

        engine.resumeAfterClarification(
                draftId, clarificationId, "beginner", "入门",
                userId, conversationId, mock(SseEmitter.class), "trace-1");

        ExecutionPlan finalPlan = JSON.parseObject(
                draftRef.get().getExecutionPlanJson(), ExecutionPlan.class);
        Map<String, String> outputs = finalPlan.getStepOutputs();

        assertThat(outputs).containsEntry("__clarification.current_level", "beginner");
        assertThat(outputs).containsKey("step-1");
        assertThat(outputs.get("step-1")).contains("current_level: beginner");
        assertThat(finalPlan.getSteps().get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(finalPlan.getStatus()).isEqualTo("COMPLETED");
    }
}
