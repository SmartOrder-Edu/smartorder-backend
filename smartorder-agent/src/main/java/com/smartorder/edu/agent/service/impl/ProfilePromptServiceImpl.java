package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.ProfileDimensionDecision;
import com.smartorder.edu.agent.service.ProfilePromptService;
import com.smartorder.edu.agent.util.PromptLoader;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProfilePromptServiceImpl implements ProfilePromptService { 

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

    /**
     * 构建维度路由器的消息列表
     * <p>
     * SystemMessage: 路由器指令模板<br>
     * conversationHistory: 最近 N 条对话消息(user/assistant 交替)<br>
     * UserMessage: 当前用户消息
     */
    public List<ChatMessage> buildRouterMessages(String currentUserMessage, List<ChatMessage> conversationHistory) {
        String routerPrompt = promptLoader.getProfileDimensionRouterPrompt();
        List<ChatMessage> messages = new ArrayList<>();
        if (routerPrompt != null && !routerPrompt.isBlank()) {
            messages.add(SystemMessage.from(routerPrompt));
        }
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            messages.addAll(conversationHistory);
        }
        messages.add(UserMessage.from(currentUserMessage));
        return messages;
    }

    /**
     * 构建维度提取器的消息列表
     * <p>
     * SystemMessage: 提取器指令模板 + 已选维度 + 维度规则<br>
     * conversationHistory: 最近 N 条对话消息<br>
     * UserMessage: 当前用户消息
     */
    public List<ChatMessage> buildExtractionMessages(String currentUserMessage, List<ChatMessage> conversationHistory,
                                                      List<ProfileDimensionDecision> decisions) {
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

        String systemPrompt = promptLoader.getProfileExtractionShellPrompt()
                .replace("{{SELECTED_DIMENSIONS_JSON}}", selectedDimensionsJson)
                .replace("{{DIMENSION_RULES}}", dimensionRules);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            messages.addAll(conversationHistory);
        }
        messages.add(UserMessage.from(currentUserMessage));
        return messages;
    }

    private String loadDimensionRule(String dimensionKey) {
        String prompt = promptLoader.getProfileDimensionPrompt(dimensionKey);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Missing profile dimension prompt: " + dimensionKey);
        }
        return prompt;
    }

    private String normalizeDimension(String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return null;
        }
        String normalized = dimensionKey.trim().toLowerCase(Locale.ROOT);
        return VALID_DIMENSIONS.contains(normalized) ? normalized : null;
    }
}
