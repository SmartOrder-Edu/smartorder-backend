package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProfileUpdateCandidateExtractorTest {

    @Test
    void shouldParseStrictJsonCandidate() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(contains("我准备考研"))).thenReturn("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "basic_profile",
                    "delta": {"learning_goal": "考研"},
                    "evidence": "我准备考研",
                    "confidence": 0.95,
                    "reason": "用户明确表达考研目标"
                  }]
                }
                """);
        LlmProfileUpdateCandidateExtractor extractor =
                new LlmProfileUpdateCandidateExtractor(chatModel);

        UpdateStudentProfileRequest result = extractor.extract("我准备考研");

        assertEquals(1, result.getDimensionUpdate().size());
        assertEquals("basic_profile", result.getDimensionUpdate().get(0).getDimensionKey());
        verify(chatModel).chat(contains("我准备考研"));
    }

    @Test
    void shouldParseJsonInsideMarkdownFence() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(contains("我没学会数据结构"))).thenReturn("""
                ```json
                {"dimensionUpdate":[]}
                ```
                """);
        LlmProfileUpdateCandidateExtractor extractor =
                new LlmProfileUpdateCandidateExtractor(chatModel);

        UpdateStudentProfileRequest result = extractor.extract("我没学会数据结构");

        assertTrue(result.getDimensionUpdate().isEmpty());
    }

    @Test
    void shouldNormalizeNullDimensionListToEmptyList() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(contains("我准备考研"))).thenReturn("{}");
        LlmProfileUpdateCandidateExtractor extractor =
                new LlmProfileUpdateCandidateExtractor(chatModel);

        UpdateStudentProfileRequest result = extractor.extract("我准备考研");

        assertTrue(result.getDimensionUpdate().isEmpty());
    }
}
