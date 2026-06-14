package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileUpdateCoordinatorTest {

    @Test
    void shouldQueueProfileAnalysisWithoutRunningItOnTheCallingThread() {
        CapturingExecutor executor = new CapturingExecutor();
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(executor, extractor, tool);

        coordinator.submit(7L, "conversation-1", "trace-1",
                "我准备考研，但是大二的时候没有怎么学会数据结构");

        assertNotNull(executor.task);
        verifyNoInteractions(extractor, tool);
    }

    @Test
    void shouldSkipOrdinaryQuestionBeforeCallingTheModel() {
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(Runnable::run, extractor, tool);

        coordinator.submit(7L, "conversation-1", "trace-1", "解释一下数据结构中的红黑树");

        verifyNoInteractions(extractor, tool);
    }

    @Test
    void shouldPassExtractedCandidatesToExistingValidatedTool() {
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(Runnable::run, extractor, tool);
        UpdateStudentProfileRequest request = requestWithGoal();
        when(extractor.extract(any())).thenReturn(request);

        coordinator.submit(7L, "conversation-1", "trace-1", "我准备考研");

        ArgumentCaptor<ToolInvokeContext> contextCaptor = ArgumentCaptor.forClass(ToolInvokeContext.class);
        verify(tool).execute(any(UpdateStudentProfileRequest.class), contextCaptor.capture());
        ToolInvokeContext context = contextCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(7L, context.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals("conversation-1", context.getConversationId());
        org.junit.jupiter.api.Assertions.assertEquals("trace-1", context.getTraceId());
        org.junit.jupiter.api.Assertions.assertEquals("我准备考研", context.getCurrentUserMessage());
    }

    @Test
    void shouldNotLetRejectedSubmissionBreakTheMainRequest() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(
                rejectingExecutor,
                mock(ProfileUpdateCandidateExtractor.class),
                mock(UpdateStudentProfileTool.class)
        );

        assertDoesNotThrow(() -> coordinator.submit(
                7L, "conversation-1", "trace-1", "我准备考研"));
    }

    @Test
    void shouldContainExtractionFailureInsideBackgroundTask() {
        ProfileUpdateCandidateExtractor extractor = mock(ProfileUpdateCandidateExtractor.class);
        UpdateStudentProfileTool tool = mock(UpdateStudentProfileTool.class);
        when(extractor.extract(any())).thenThrow(new IllegalStateException("model unavailable"));
        ProfileUpdateCoordinator coordinator = new ProfileUpdateCoordinator(Runnable::run, extractor, tool);

        assertDoesNotThrow(() -> coordinator.submit(
                7L, "conversation-1", "trace-1", "我准备考研"));

        verifyNoInteractions(tool);
    }

    private UpdateStudentProfileRequest requestWithGoal() {
        UpdateStudentProfileRequest.DimensionUpdate update =
                new UpdateStudentProfileRequest.DimensionUpdate();
        update.setDimensionKey("basic_profile");
        update.setDelta(Map.of("learning_goal", "考研"));
        update.setEvidence("我准备考研");
        update.setConfidence(0.95);
        update.setReason("用户明确表达考研目标");

        UpdateStudentProfileRequest request = new UpdateStudentProfileRequest();
        request.setDimensionUpdate(List.of(update));
        return request;
    }

    private static class CapturingExecutor implements Executor {
        private Runnable task;

        @Override
        public void execute(Runnable command) {
            this.task = command;
        }
    }
}
