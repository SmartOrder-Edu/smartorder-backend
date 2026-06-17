package com.smartorder.edu.agent.profile;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.prompt.PromptLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProfilePromptService {

    private static final Set<String> VALID_DIMENSIONS = Set.of(
            "learning_background",
            "learning_goals",
            "ability_profile",
            "common_misconceptions",
            "learning_preference",
            "metacognitive_profile",
            "subject_profiles"
    );

    private final PromptLoader promptLoader;

    public String buildRouterPrompt(String currentUserMessage, String conversationHistory) {
        return promptLoader.getProfileDimensionRouterPrompt()
                .replace("{{CONVERSATION_HISTORY}}", nullToEmpty(conversationHistory))
                .replace("{{CURRENT_USER_MESSAGE}}", currentUserMessage);
    }

    public String buildExtractionPrompt(String currentUserMessage, String conversationHistory, List<ProfileDimensionDecision> decisions) {
        List<ProfileDimensionDecision> validDecisions = decisions == null
                ? List.of()
                : decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> normalizeDimension(decision.getDimensionKey()) != null)
                .toList();

        String selectedDimensionsJson = JSON.toJSONString(validDecisions);
        String dimensionRules = validDecisions.stream()
                .map(ProfileDimensionDecision::getDimensionKey)
                .map(this::normalizeDimension)
                .distinct()
                .map(this::loadDimensionRule)
                .collect(Collectors.joining("\n\n---\n\n"));

        return promptLoader.getProfileExtractionShellPrompt()
                .replace("{{CONVERSATION_HISTORY}}", nullToEmpty(conversationHistory))
                .replace("{{CURRENT_USER_MESSAGE}}", currentUserMessage)
                .replace("{{SELECTED_DIMENSIONS_JSON}}", selectedDimensionsJson)
                .replace("{{DIMENSION_RULES}}", dimensionRules);
    }

    private String loadDimensionRule(String dimensionKey) {
        String prompt = promptLoader.getProfileDimensionPrompt(dimensionKey);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Missing profile dimension prompt: " + dimensionKey);
        }
        return prompt;
    }

    private static String nullToEmpty(String value) {
        return value == null || value.isBlank() ? "（无历史对话）" : value;
    }

    private String normalizeDimension(String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return null;
        }
        String normalized = dimensionKey.trim().toLowerCase(Locale.ROOT);
        return VALID_DIMENSIONS.contains(normalized) ? normalized : null;
    }
}
