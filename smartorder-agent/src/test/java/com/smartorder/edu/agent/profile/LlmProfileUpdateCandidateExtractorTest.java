package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.prompt.PromptLoader;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

        List<ChatMessage> history = List.of(UserMessage.from("history text"));
        UpdateStudentProfileRequest request = extractor.extract("我想两个月内补数据结构", history);

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

        List<ChatMessage> history = List.of(
                UserMessage.from("你好"),
                AiMessage.from("你好！有什么可以帮你的？")
        );
        extractor.extract("我想学数据结构", history);

        assertEquals(1, chatModel.prompts.size());
        String prompt = chatModel.prompts.get(0);
        assertTrue(prompt.contains("你好"));
        assertTrue(prompt.contains("你好！有什么可以帮你的？"));
    }

    private ProfilePromptService promptService() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.getProfileDimensionRouterPrompt())
                .thenReturn("router instructions");
        when(promptLoader.getProfileExtractionShellPrompt())
                .thenReturn("extract shell\ndim={{SELECTED_DIMENSIONS_JSON}}\nrules={{DIMENSION_RULES}}");
        when(promptLoader.getProfileDimensionPrompt("learning_goals"))
                .thenReturn("维度：learning_goals");
        when(promptLoader.getProfileDimensionPrompt("learning_background"))
                .thenReturn("维度：learning_background");
        return new ProfilePromptService(promptLoader);
    }

    /**
     * ChatModel 测试替身，通过 chat(ChatRequest) 接收结构化消息，
     * 将全部消息文本拼接后记录到 prompts 列表供断言检查。
     */
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

        @Override
        public ChatResponse chat(ChatRequest request) {
            String fullText = request.messages().stream()
                    .map(m -> {
                        if (m instanceof dev.langchain4j.data.message.SystemMessage sm) return sm.text();
                        if (m instanceof dev.langchain4j.data.message.UserMessage um) return um.singleText();
                        if (m instanceof dev.langchain4j.data.message.AiMessage am) return am.text();
                        return "";
                    })
                    .collect(Collectors.joining("\n"));
            prompts.add(fullText);
            String responseText = responses.get(prompts.size() - 1);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responseText))
                    .build();
        }
    }
}
