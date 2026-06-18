package com.smartorder.edu.agent.profile;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Uses a small JSON-capable model to extract profile update candidates from the current user message
 * with conversation history as structured LangChain4j messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmProfileUpdateCandidateExtractor implements ProfileUpdateCandidateExtractor {

    @Qualifier("profileUpdateChatModel")
    private final ChatModel chatModel;

    private final ProfilePromptService profilePromptService;

    @Override
    public UpdateStudentProfileRequest extract(String currentUserMessage, List<ChatMessage> conversationHistory) {
        if (currentUserMessage == null || currentUserMessage.isBlank()) {
            return emptyRequest();
        }

        try {
            // 1. 维度路由：SystemMessage(指令) + 历史消息 + UserMessage(当前消息)
            ChatResponse routerResponse = chatModel.chat(ChatRequest.builder()
                    .messages(profilePromptService.buildRouterMessages(currentUserMessage, conversationHistory))
                    .build());
            ProfileDimensionSelectionResult selection = JSON.parseObject(
                    extractJson(routerResponse.aiMessage().text()),
                    ProfileDimensionSelectionResult.class
            );
            if (selection == null || selection.getDimensions() == null || selection.getDimensions().isEmpty()) {
                return emptyRequest();
            }

            // 2. 维度提取：SystemMessage(指令+已选维度+规则) + 历史消息 + UserMessage(当前消息)
            ChatResponse extractionResponse = chatModel.chat(ChatRequest.builder()
                    .messages(profilePromptService.buildExtractionMessages(
                            currentUserMessage, conversationHistory, selection.getDimensions()))
                    .build());
            UpdateStudentProfileRequest request = JSON.parseObject(
                    extractJson(extractionResponse.aiMessage().text()),
                    UpdateStudentProfileRequest.class
            );
            if (request == null) {
                return emptyRequest();
            }
            if (request.getDimensionUpdate() == null) {
                request.setDimensionUpdate(List.of());
            }
            return request;
        } catch (Exception e) {
            log.warn("[ProfileUpdateExtractor] Failed to extract profile update candidate: {}", e.getMessage());
            return emptyRequest();
        }
    }

    private UpdateStudentProfileRequest emptyRequest() {
        UpdateStudentProfileRequest request = new UpdateStudentProfileRequest();
        request.setDimensionUpdate(List.of());
        return request;
    }

    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            log.debug("[ProfileUpdateExtractor] LLM 返回空内容，使用空结果");
            return "{\"dimensionUpdate\":[]}";
        }
        String trimmed = response.trim();
        // 剥离 markdown 代码块包裹：```json ... ``` 或 ``` ... ```
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
            trimmed = trimmed.trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("[ProfileUpdateExtractor] LLM 返回内容不是合法 JSON, 原始内容前200字符: {}",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            throw new IllegalArgumentException("Profile update extractor result is not valid JSON: "
                    + (response.length() > 100 ? response.substring(0, 100) + "..." : response));
        }
        return trimmed.substring(start, end + 1);
    }
}
