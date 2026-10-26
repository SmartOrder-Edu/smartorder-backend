package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.ProfileDimensionDecision;
import com.smartorder.edu.agent.model.dto.ProfileDimensionSelectionResult;
import com.smartorder.edu.agent.service.ProfilePromptService;
import com.smartorder.edu.agent.service.ProfileUpdateCandidateExtractor;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
            Set<String> routedDimensions = selection.getDimensions().stream()
                    .map(ProfileDimensionDecision::getDimensionKey)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<String> extractedDimensions = request.getDimensionUpdate().stream()
                    .map(UpdateStudentProfileRequest.DimensionUpdate::getDimensionKey)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<ProfileDimensionDecision> missingDecisions = findMissingDecisions(
                    selection.getDimensions(), request.getDimensionUpdate());
            if (!missingDecisions.isEmpty()) {
                List<ProfileDimensionDecision> retryDecisions = missingDecisions;
                try {
                    UpdateStudentProfileRequest retryRequest = JSON.parseObject(
                            extractJson(chatModel.chat(ChatRequest.builder()
                                    .messages(profilePromptService.buildExtractionMessages(
                                            currentUserMessage, conversationHistory, retryDecisions))
                                    .build()).aiMessage().text()),
                            UpdateStudentProfileRequest.class
                    );
                    if (retryRequest != null && retryRequest.getDimensionUpdate() != null) {
                        List<UpdateStudentProfileRequest.DimensionUpdate> mergedUpdates = new ArrayList<>(request.getDimensionUpdate());
                        retryRequest.getDimensionUpdate().stream()
                                .filter(update -> coversAnyMissingDecision(update, retryDecisions))
                                .forEach(mergedUpdates::add);
                        request.setDimensionUpdate(mergedUpdates);
                        extractedDimensions = request.getDimensionUpdate().stream()
                                .map(UpdateStudentProfileRequest.DimensionUpdate::getDimensionKey)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());
                        missingDecisions = findMissingDecisions(selection.getDimensions(), request.getDimensionUpdate());
                    }
                } catch (Exception retryException) {
                    log.warn("[ProfileUpdateExtractor] Missing-dimension retry failed, keeping initial extraction: {}",
                            retryException.getMessage());
                }
            }
            log.info("[ProfileUpdateExtractor] routedDimensions={}, extractedDimensions={}, routedFactCount={}, missingFactCount={}, missingDimensionKeys={}",
                    routedDimensions, extractedDimensions, selection.getDimensions().size(), missingDecisions.size(),
                    missingDecisions.stream().map(ProfileDimensionDecision::getDimensionKey).filter(Objects::nonNull).toList());
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

    private List<ProfileDimensionDecision> findMissingDecisions(
            List<ProfileDimensionDecision> decisions,
            List<UpdateStudentProfileRequest.DimensionUpdate> updates) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        List<UpdateStudentProfileRequest.DimensionUpdate> safeUpdates = updates == null ? List.of() : updates;
        return decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> safeUpdates.stream().noneMatch(update -> coversDecision(update, decision)))
                .toList();
    }

    private boolean coversAnyMissingDecision(UpdateStudentProfileRequest.DimensionUpdate update,
                                             List<ProfileDimensionDecision> missingDecisions) {
        return missingDecisions.stream().anyMatch(decision -> coversDecision(update, decision));
    }

    private boolean coversDecision(UpdateStudentProfileRequest.DimensionUpdate update,
                                   ProfileDimensionDecision decision) {
        if (update == null || decision == null || !Objects.equals(update.getDimensionKey(), decision.getDimensionKey())) {
            return false;
        }
        String decisionEvidence = decision.getEvidence();
        return decisionEvidence == null || decisionEvidence.isBlank()
                || (update.getEvidence() != null && update.getEvidence().contains(decisionEvidence));
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
