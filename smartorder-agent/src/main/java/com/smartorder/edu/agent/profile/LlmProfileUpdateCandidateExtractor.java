package com.smartorder.edu.agent.profile;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Uses a small JSON-capable model to extract profile update candidates from the current user message only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmProfileUpdateCandidateExtractor implements ProfileUpdateCandidateExtractor {

    @Qualifier("profileUpdateChatModel")
    private final ChatModel chatModel;

    private final ProfilePromptService profilePromptService;

    @Override
    public UpdateStudentProfileRequest extract(String currentUserMessage, String conversationHistory) {
        if (currentUserMessage == null || currentUserMessage.isBlank()) {
            return emptyRequest();
        }

        try {
            String routerResponse = chatModel.chat(
                    profilePromptService.buildRouterPrompt(currentUserMessage, conversationHistory));
            ProfileDimensionSelectionResult selection = JSON.parseObject(
                    extractJson(routerResponse),
                    ProfileDimensionSelectionResult.class
            );
            if (selection == null || selection.getDimensions() == null || selection.getDimensions().isEmpty()) {
                return emptyRequest();
            }

            String extractionResponse = chatModel.chat(
                    profilePromptService.buildExtractionPrompt(currentUserMessage, conversationHistory, selection.getDimensions())
            );
            UpdateStudentProfileRequest request = JSON.parseObject(
                    extractJson(extractionResponse),
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
            return "{\"dimensionUpdate\":[]}";
        }
        String trimmed = response.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Profile update extractor result is not valid JSON");
        }
        return trimmed.substring(start, end + 1);
    }
}
