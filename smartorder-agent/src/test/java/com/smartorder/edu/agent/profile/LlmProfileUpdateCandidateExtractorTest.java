package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.prompt.PromptLoader;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmProfileUpdateCandidateExtractorTest {

    @Test
    void shouldStopAfterRouterWhenMessageHasNoProfileEvidence() {
        RecordingChatModel chatModel = new RecordingChatModel(
                "{\"dimensions\":[]}"
        );
        LlmProfileUpdateCandidateExtractor extractor = new LlmProfileUpdateCandidateExtractor(
                chatModel,
                promptService()
        );

        UpdateStudentProfileRequest request = extractor.extract("帮我解释一下链表", null);

        assertNotNull(request);
        assertNotNull(request.getDimensionUpdate());
        assertTrue(request.getDimensionUpdate().isEmpty());
        assertEquals(1, chatModel.prompts.size());
        assertTrue(chatModel.prompts.get(0).contains("帮我解释一下链表"));
        assertTrue(chatModel.prompts.get(0).contains("（无历史对话）"));
    }

    @Test
    void shouldGenerateDeltaUsingOnlySelectedDimensionRules() {
        RecordingChatModel chatModel = new RecordingChatModel(
                """
                {"dimensions":[{"dimensionKey":"learning_goals","evidence":"我想两个月内补数据结构","confidence":0.95,"reason":"明确学习目标"}]}
                """,
                """
                {"dimensionUpdate":[{"dimensionKey":"learning_goals","delta":{"goals":{"GOAL-DATA_STRUCTURES-001":{"goal_id":"GOAL-DATA_STRUCTURES-001","scope":"SUBJECT","subject_id":"DATA_STRUCTURES","title":"两个月内补数据结构","status":"IN_PROGRESS"}}},"evidence":"我想两个月内补数据结构","confidence":0.95,"reason":"用户明确表达学习目标"}]}
                """
        );
        LlmProfileUpdateCandidateExtractor extractor = new LlmProfileUpdateCandidateExtractor(
                chatModel,
                promptService()
        );

        UpdateStudentProfileRequest request = extractor.extract("我想两个月内补数据结构", "history text");

        assertEquals(2, chatModel.prompts.size());
        String extractionPrompt = chatModel.prompts.get(1);
        assertTrue(extractionPrompt.contains("维度：learning_goals"));
        assertFalse(extractionPrompt.contains("维度：learning_background"));
        assertEquals(1, request.getDimensionUpdate().size());
        UpdateStudentProfileRequest.DimensionUpdate update = request.getDimensionUpdate().get(0);
        assertEquals("learning_goals", update.getDimensionKey());
        assertTrue(update.getDelta().containsKey("goals"));
    }

    @Test
    void shouldIncludeHistoryInRouterPrompt() {
        RecordingChatModel chatModel = new RecordingChatModel(
                "{\"dimensions\":[]}"
        );
        LlmProfileUpdateCandidateExtractor extractor = new LlmProfileUpdateCandidateExtractor(
                chatModel,
                promptService()
        );

        extractor.extract("我想学数据结构", "[user]: 你好\n\n[assistant]: 你好！有什么可以帮你的？\n\n");

        assertEquals(1, chatModel.prompts.size());
        String prompt = chatModel.prompts.get(0);
        assertTrue(prompt.contains("[user]: 你好"));
        assertTrue(prompt.contains("[assistant]: 你好！有什么可以帮你的？"));
    }

    private ProfilePromptService promptService() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.getProfileDimensionRouterPrompt())
                .thenReturn("router\nhistory={{CONVERSATION_HISTORY}}\nmsg={{CURRENT_USER_MESSAGE}}");
        when(promptLoader.getProfileExtractionShellPrompt())
                .thenReturn("extract\nhistory={{CONVERSATION_HISTORY}}\nmsg={{CURRENT_USER_MESSAGE}}\ndim={{SELECTED_DIMENSIONS_JSON}}\nrules={{DIMENSION_RULES}}");
        when(promptLoader.getProfileDimensionPrompt("learning_goals"))
                .thenReturn("维度：learning_goals");
        when(promptLoader.getProfileDimensionPrompt("learning_background"))
                .thenReturn("维度：learning_background");
        return new ProfilePromptService(promptLoader);
    }

    private static class RecordingChatModel implements ChatModel {

        private final List<String> responses;
        private final List<String> prompts = new ArrayList<>();

        RecordingChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String chat(String userMessage) {
            prompts.add(userMessage);
            return responses.get(prompts.size() - 1);
        }
    }
}
