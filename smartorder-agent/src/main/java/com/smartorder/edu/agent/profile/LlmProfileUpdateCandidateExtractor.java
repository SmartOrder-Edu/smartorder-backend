package com.smartorder.edu.agent.profile;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Uses a lightweight, current-message-only LLM call to propose profile updates.
 */
@Component
@RequiredArgsConstructor
public class LlmProfileUpdateCandidateExtractor implements ProfileUpdateCandidateExtractor {

    private final ChatModel chatLanguageModel;

    @Override
    public UpdateStudentProfileRequest extract(String currentUserMessage) {
        String response = chatLanguageModel.chat(buildPrompt(currentUserMessage));
        UpdateStudentProfileRequest request = JSON.parseObject(
                extractJson(response),
                UpdateStudentProfileRequest.class
        );
        if (request == null) {
            request = new UpdateStudentProfileRequest();
        }
        if (request.getDimensionUpdate() == null) {
            request.setDimensionUpdate(List.of());
        }
        return request;
    }

    private String buildPrompt(String currentUserMessage) {
        return """
                你是学生画像候选信息提取器，只分析下面这一条当前用户消息。

                仅当用户明确自述以下信息时才提取：
                - basic_profile：年级、专业、备考目标、学习目标
                - knowledge_profile：明确掌握或不掌握的知识
                - ability_profile：明确自述的能力强弱
                - preference_profile / cognitive_profile：稳定学习偏好或理解方式
                - weakness_profile：明确没有学会、不理解、基础薄弱或反复做错的知识
                - behavior_profile：稳定学习习惯和高效时段
                - emotion_profile：明确的学习焦虑、紧张或压力

                普通提问、请求讲解、一次性输出格式要求不是画像证据。
                evidence 必须逐字摘自当前用户消息，confidence 必须在 0.8 到 1.0。
                没有可靠证据时返回 {"dimensionUpdate":[]}。
                只输出严格 JSON，不要输出 Markdown 或解释。

                输出结构：
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "weakness_profile",
                    "delta": {"weak_points": [{"topic": "数据结构", "weakness_type": "foundation_gap"}]},
                    "evidence": "大二的时候没有怎么学会数据结构",
                    "confidence": 0.92,
                    "reason": "用户明确说明数据结构基础学习不足"
                  }]
                }

                当前用户消息：
                %s
                """.formatted(currentUserMessage);
    }

    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "{\"dimensionUpdate\":[]}";
        }
        String trimmed = response.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("画像候选提取结果不是有效 JSON");
        }
        return trimmed.substring(start, end + 1);
    }
}
