package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileUpdateCoordinatorTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void shouldIgnoreBlankMessageBeforeCallingExtractor() {
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(
                DIRECT_EXECUTOR,
                extractor,
                tool
        );

        coordinator.submit(1L, "conversation-1", "trace-1", " ", null);

        verify(extractor, never()).extract(any(), any());
        verify(tool, never()).execute(any(), any());
    }

    @Test
    void shouldSkipToolWhenExtractorReturnsNoUpdates() {
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileRequest emptyRequest = new UpdateStudentProfileRequest();
        emptyRequest.setDimensionUpdate(List.of());
        List<ChatMessage> history = List.of(UserMessage.from("some history"));
        when(extractor.extract(eq("我计划两个月内补数据结构"), any())).thenReturn(emptyRequest);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(
                DIRECT_EXECUTOR,
                extractor,
                tool
        );

        coordinator.submit(1L, "conversation-1", "trace-1", "我计划两个月内补数据结构", history);

        verify(extractor).extract(eq("我计划两个月内补数据结构"), eq(history));
        verify(tool, never()).execute(any(), any());
    }

    @Test
    void shouldPassOriginalUserMessageToUpdateToolContext() {
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileRequest request = requestWithLearningGoal();
        when(extractor.extract(eq("我计划两个月内补数据结构"), any())).thenReturn(request);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(
                DIRECT_EXECUTOR,
                extractor,
                tool
        );

        coordinator.submit(7L, "conversation-1", "trace-1", "我计划两个月内补数据结构",
                List.of(UserMessage.from("some history")));

        ArgumentCaptor<ToolInvokeContext> contextCaptor = ArgumentCaptor.forClass(ToolInvokeContext.class);
        verify(tool).execute(eq(request), contextCaptor.capture());
        ToolInvokeContext context = contextCaptor.getValue();
        assertEquals(7L, context.getUserId());
        assertEquals("conversation-1", context.getConversationId());
        assertEquals("trace-1", context.getTraceId());
        assertEquals("我计划两个月内补数据结构", context.getCurrentUserMessage());
        assertEquals("ProfileUpdateCoordinator", context.getAgentName());
    }

    @Test
    void shouldRecognizeRejectedToolResultInsteadOfReportingCompletion() {
        assertEquals(false, ProfileUpdateCoordinator.isSuccessfulToolResult(
                Map.of("success", false, "error", "evidence 不匹配")));
        assertEquals(true, ProfileUpdateCoordinator.isSuccessfulToolResult(
                Map.of("success", true, "dimensions", List.of("learning_goals"))));
    }

    private UpdateStudentProfileRequest requestWithLearningGoal() {
        UpdateStudentProfileRequest.DimensionUpdate update = new UpdateStudentProfileRequest.DimensionUpdate();
        update.setDimensionKey("learning_goals");
        update.setDelta(Map.of(
                "goals",
                Map.of("GOAL-DATA_STRUCTURES-001", Map.of("title", "两个月内补数据结构"))
        ));
        update.setEvidence("我想两个月内补数据结构");
        update.setConfidence(0.95);
        update.setReason("用户明确表达学习目标");

        UpdateStudentProfileRequest request = new UpdateStudentProfileRequest();
        request.setDimensionUpdate(List.of(update));
        return request;
    }
}
