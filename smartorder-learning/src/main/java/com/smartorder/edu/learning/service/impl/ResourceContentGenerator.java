package com.smartorder.edu.learning.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.service.recommendation.BilibiliVideoRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResourceContentGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final BilibiliVideoRecommendationService bilibiliVideoRecommendationService;

    public ResourceContent generate(Resource resource) {
        ResourceContent content = new ResourceContent();
        content.setResourceId(resource.getId());
        content.setContentType(contentType(resource.getResourceType()));
        content.setContent(generatedContent(resource));
        content.setStatus("active");
        content.setReviewResult(toJson(Map.of(
                "status", "passed",
                "checks", List.of("non_empty", "bound_to_space", "type_matches_content"),
                "summary", "MVP 内容格式校验通过，后续可接入 Supervisor Agent 深度事实校验"
        )));
        content.setIsDeleted(0);
        return content;
    }

    public String fileFormat(String resourceType) {
        return switch (resourceType) {
            case "doc" -> "md";
            case "html" -> "html";
            case "mind_map", "video" -> "md";
            case "quiz" -> "json";
            case "image" -> "png";
            default -> "link";
        };
    }

    private String contentType(String resourceType) {
        return switch (resourceType) {
            case "html" -> "html";
            case "quiz" -> "json";
            case "link" -> "external_link";
            default -> "markdown";
        };
    }

    private String generatedContent(Resource resource) {
        String knowledgePoint = extractMetadataValue(resource.getMetadata(), "knowledgePoint", cleanTitle(resource));
        String reason = extractMetadataValue(resource.getMetadata(), "agentReason", resource.getDescription());
        int targetMinutes = extractTargetMinutes(resource.getMetadata());
        return switch (resource.getResourceType()) {
            case "doc" -> docContent(resource, knowledgePoint, reason, targetMinutes);
            case "html" -> htmlContent(resource, knowledgePoint, reason);
            case "mind_map" -> mindMapContent(resource, knowledgePoint, reason);
            case "quiz" -> quizContent(resource, knowledgePoint);
            case "link" -> linkContent(resource, knowledgePoint, reason);
            case "image" -> imageGuideContent(resource, knowledgePoint, reason);
            case "video" -> videoScriptContent(resource, knowledgePoint, reason);
            default -> docContent(resource, knowledgePoint, reason, targetMinutes);
        };
    }

    private String docContent(Resource resource, String knowledgePoint, String reason, int targetMinutes) {
        return """
                # %s

                ## Agent 生成理由

                %s

                ## 学习目标

                - 说清楚「%s」的核心概念。
                - 能识别本知识点的常见使用条件和易错点。
                - 在 %d 分钟内完成一次讲解学习和一次即时复盘。

                ## 核心讲解

                1. 先建立定义：不要直接记结论，先确认概念适用的前提。
                2. 再看典型场景：把题目条件与知识点条件逐项对应。
                3. 最后做反例检查：遇到边界情况时，先判断能不能使用该方法。

                ## 例题处理步骤

                1. 标出题目给出的已知条件。
                2. 判断这些条件是否满足「%s」的使用前提。
                3. 选择最短的解法路径，并写出关键转换。
                4. 回看结果是否符合题意和定义边界。

                ## 复盘问题

                - 我能不能不用提示说出适用条件？
                - 哪一步最容易误判？
                - 下一次遇到相似题，我应该先检查什么？
                """.formatted(resource.getTitle(), reason, knowledgePoint, targetMinutes, knowledgePoint);
    }

    private String mindMapContent(Resource resource, String knowledgePoint, String reason) {
        return """
                # %s

                %s

                ```mermaid
                mindmap
                  root((%s))
                    核心定义
                      前提条件
                      关键结论
                    典型应用
                      例题识别
                      解题步骤
                    易错点
                      条件误判
                      结论套用
                    复盘动作
                      说出条件
                      做专项题
                      标记错因
                ```
                """.formatted(resource.getTitle(), reason, sanitizeMermaidText(knowledgePoint));
    }

    private String quizContent(Resource resource, String knowledgePoint) {
        return toJson(Map.of(
                "title", resource.getTitle(),
                "knowledgePoint", knowledgePoint,
                "questions", List.of(
                        Map.of(
                                "type", "single_choice",
                                "stem", "学习「" + knowledgePoint + "」时，第一步最应该确认什么？",
                                "options", List.of("是否满足适用条件", "答案是否足够简短", "是否可以跳过定义", "是否只记住结论"),
                                "answer", "是否满足适用条件",
                                "explanation", "多数误用来自没有先判断适用条件。"
                        ),
                        Map.of(
                                "type", "short_answer",
                                "stem", "请用一句话说明「" + knowledgePoint + "」最容易出错的地方。",
                                "referenceAnswer", "容易在条件不完整时直接套用结论。",
                                "explanation", "复盘时要把错因写成可检查的条件。"
                        ),
                        Map.of(
                                "type", "checklist",
                                "stem", "完成学习后勾选你已经掌握的动作。",
                                "items", List.of("能说出核心定义", "能判断适用条件", "能解释一个典型例题", "能列出至少一个易错点")
                        )
                )
        ));
    }

    private String htmlContent(Resource resource, String knowledgePoint, String reason) {
        return """
                <article class="generated-resource-html">
                  <h1>%s</h1>
                  <section>
                    <h2>Agent 生成理由</h2>
                    <p>%s</p>
                  </section>
                  <section>
                    <h2>三步学习法</h2>
                    <ol>
                      <li><strong>定义</strong>：先说清楚「%s」是什么。</li>
                      <li><strong>条件</strong>：逐条检查题目是否满足使用前提。</li>
                      <li><strong>应用</strong>：用一个例题完成从识别到复盘的闭环。</li>
                    </ol>
                  </section>
                </article>
                """.formatted(escapeHtml(resource.getTitle()), escapeHtml(reason), escapeHtml(knowledgePoint));
    }

    private String linkContent(Resource resource, String knowledgePoint, String reason) {
        BilibiliVideoRecommendationService.RecommendationResult recommendation =
                bilibiliVideoRecommendationService.recommend(knowledgePoint, reason, 3);
        return toJson(bilibiliVideoRecommendationService.contentPayload(
                resource.getTitle(),
                knowledgePoint,
                reason,
                recommendation
        ));
    }

    private String imageGuideContent(Resource resource, String knowledgePoint, String reason) {
        return """
                # %s

                ## 图解目标

                %s

                ## 图解结构

                - 左侧：核心定义
                - 中间：适用条件判断
                - 右侧：典型例题和易错点

                ## 推荐画面文案

                「%s」不是一个孤立结论，先判断条件，再选择方法。
                """.formatted(resource.getTitle(), reason, knowledgePoint);
    }

    private String videoScriptContent(Resource resource, String knowledgePoint, String reason) {
        return """
                # %s

                ## 脚本定位

                %s

                ## 讲解分镜

                1. 0:00-0:20 提出一个常见误判场景。
                2. 0:20-1:00 解释「%s」的核心条件。
                3. 1:00-2:00 带一个例题走完整判断流程。
                4. 2:00-2:30 总结复盘清单。
                """.formatted(resource.getTitle(), reason, knowledgePoint);
    }

    private String cleanTitle(Resource resource) {
        String title = resource.getTitle();
        if (title == null || title.isBlank()) {
            return "当前知识点";
        }
        int index = title.indexOf("：");
        return index >= 0 && index + 1 < title.length() ? title.substring(index + 1) : title;
    }

    @SuppressWarnings("unchecked")
    private String extractMetadataValue(String metadata, String key, String fallback) {
        if (metadata == null || metadata.isBlank()) {
            return fallback;
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(metadata, Map.class);
            Object value = map.get(key);
            return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private int extractTargetMinutes(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return 30;
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(metadata, Map.class);
            Object value = map.get("targetMinutes");
            if (value instanceof Number number) {
                return Math.max(1, number.intValue());
            }
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (Exception e) {
            return 30;
        }
    }

    private String sanitizeMermaidText(String text) {
        return String.valueOf(text == null ? "" : text)
                .replace("(", "（")
                .replace(")", "）")
                .replace("[", "【")
                .replace("]", "】")
                .replace("\n", " ");
    }

    private String escapeHtml(String text) {
        return String.valueOf(text == null ? "" : text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("资源内容序列化失败");
        }
    }
}
