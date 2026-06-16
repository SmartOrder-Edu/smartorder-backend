package com.smartorder.edu.agent.profile;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于LLM的学生画像候选信息提取器，仅分析当前消息轻量调用
 */
@Component
@RequiredArgsConstructor
public class LlmProfileUpdateCandidateExtractor implements ProfileUpdateCandidateExtractor {

    @Qualifier("chatLanguageModel")
    private final ChatModel chatModel;

    /** 通过LLM分析当前用户消息，提取学生画像候选更新 **/
    @Override
    public UpdateStudentProfileRequest extract(String currentUserMessage) {
        String response = chatModel.chat(buildPrompt(currentUserMessage));
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

    /** 构建LLM提示词，要求从用户消息中提取画像信息并返回JSON **/
    private String buildPrompt(String currentUserMessage) {
        return """
                你是学生画像候选信息提取器，只分析下面这一条当前用户消息。

                仅当用户明确自述以下信息时才提取：
                - learning_background：学历、年级、专业、已修课程、编程经验、学习环境
                - learning_goals：长期目标、阶段目标、优先级、截止时间、时间预算
                - ability_profile：明确自述的能力强弱（概念理解、问题分析、代码实现等）
                - common_misconceptions：明确没有学会、不理解、基础薄弱或反复做错的知识
                - learning_preference：稳定学习偏好（资源形式、讲解方式、练习方式等）
                - metacognitive_profile：学习规划、自我评估、反思、主动求助能力
                - subject_profiles：特定科目的知识掌握、易错点、学习行为

                普通提问、请求讲解、一次性输出格式要求不是画像证据。
                evidence 必须逐字摘自当前用户消息，confidence 必须在 0.8 到 1.0。
                没有可靠证据时返回 {"dimensionUpdate":[]}。
                只输出严格 JSON，不要输出 Markdown 或解释。

                输出结构：
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "common_misconceptions",
                    "delta": {
                      "patterns": {
                        "DATA_STRUCTURE_FOUNDATION": {
                          "misconception_id": "DATA_STRUCTURE_FOUNDATION",
                          "title": "数据结构基础薄弱",
                          "description": "用户明确表示之前没有学会数据结构",
                          "status": "ACTIVE"
                        }
                      }
                    },
                    "evidence": "大二的时候没有怎么学会数据结构",
                    "confidence": 0.92,
                    "reason": "用户明确说明数据结构基础学习不足"
                  }]
                }

                当前用户消息：
                %s
                """.formatted(currentUserMessage);
    }

    /** 从LLM响应中提取JSON部分，去除可能的Markdown包裹 **/
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
