package com.smartorder.edu.learning.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.service.ResourceGenerationContext;
import com.smartorder.edu.learning.service.ResourceGenerationContextBuilder;
import com.smartorder.edu.learning.service.recommendation.BilibiliVideoRecommendationService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceContentGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DOC_PROMPT_VERSION = "resource-doc-v1";
    private static final String QUIZ_PROMPT_VERSION = "resource-quiz-v1";
    private static final int MIN_QUIZ_QUESTION_COUNT = 3;
    private static final int MAX_QUIZ_QUESTION_COUNT = 10;
    private final BilibiliVideoRecommendationService bilibiliVideoRecommendationService;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ResourceGenerationContextBuilder contextBuilder;

    public ResourceContent generate(Resource resource) {
        return generate(resource, null);
    }

    public ResourceContent generate(Resource resource, Long userId) {
        ResourceGenerationContext context = userId == null ? null : contextBuilder.build(userId, resource);
        GeneratedContent generated = generatedContent(resource, context);
        ResourceContent content = new ResourceContent();
        content.setResourceId(resource.getId());
        content.setContentType(generated.contentType());
        content.setContent(generated.content());
        content.setStatus("active");
        content.setReviewResult(generated.reviewResult());
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

    private GeneratedContent generatedContent(Resource resource, ResourceGenerationContext context) {
        String knowledgePoint = context == null
                ? extractMetadataValue(resource.getMetadata(), "knowledgePoint", cleanTitle(resource))
                : context.knowledgePoint();
        String reason = context == null
                ? extractMetadataValue(resource.getMetadata(), "agentReason", resource.getDescription())
                : context.reason();
        int targetMinutes = context == null ? extractTargetMinutes(resource.getMetadata()) : context.targetMinutes();
        return switch (resource.getResourceType()) {
            case "doc" -> docGeneratedContent(resource, context, knowledgePoint, reason, targetMinutes);
            case "html" -> templateGeneratedContent("html", htmlContent(resource, knowledgePoint, reason));
            case "mind_map" -> templateGeneratedContent("markdown", mindMapContent(resource, knowledgePoint, reason));
            case "quiz" -> quizGeneratedContent(resource, context, knowledgePoint, reason, targetMinutes);
            case "link" -> templateGeneratedContent("external_link", linkContent(resource, knowledgePoint, reason));
            case "image" -> templateGeneratedContent("markdown", imageGuideContent(resource, knowledgePoint, reason));
            case "video" -> templateGeneratedContent("markdown", videoScriptContent(resource, knowledgePoint, reason));
            default -> docGeneratedContent(resource, context, knowledgePoint, reason, targetMinutes);
        };
    }

    private GeneratedContent docGeneratedContent(
            Resource resource,
            ResourceGenerationContext context,
            String knowledgePoint,
            String reason,
            int targetMinutes
    ) {
        String fallback = docContent(resource, knowledgePoint, reason, targetMinutes);
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null || context == null) {
            return new GeneratedContent("markdown", fallback,
                    reviewResult("passed", "template", 70,
                            List.of(check("format_valid", true), check("fallback_template", true)),
                            "未发现可用 LLM 或缺少生成上下文，已使用模板内容。", null));
        }

        try {
            String content = sanitizeMarkdown(chatModel.chat(buildDocPrompt(context)));
            List<Map<String, Object>> checks = validateDocContent(content, context.knowledgePoint());
            boolean passed = checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
            if (!passed) {
                return new GeneratedContent("markdown", fallback,
                        reviewResult("needs_review", "template_fallback", 60, checks,
                                "LLM 输出未通过基础格式校验，已使用模板内容降级。", preview(content)));
            }
            return new GeneratedContent("markdown", content,
                    reviewResult("passed", "llm", 86, checks,
                            "LLM 生成内容通过基础格式校验，适合进入学习资料库。", null));
        } catch (Exception e) {
            log.warn("[ResourceContentGenerator] LLM doc generation failed, fallback to template. resourceId={}",
                    resource.getId(), e);
            return new GeneratedContent("markdown", fallback,
                    reviewResult("needs_review", "template_fallback", 55,
                            List.of(check("llm_call_success", false), check("fallback_template", true)),
                            "LLM 调用失败，已使用模板内容降级：" + e.getMessage(), null));
        }
    }

    private GeneratedContent quizGeneratedContent(
            Resource resource,
            ResourceGenerationContext context,
            String knowledgePoint,
            String reason,
            int targetMinutes
    ) {
        String fallback = quizContent(resource, knowledgePoint);
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null || context == null) {
            return new GeneratedContent("json", fallback,
                    reviewResult("passed", "template", 70,
                            List.of(check("format_valid", true), check("fallback_template", true)),
                            "未发现可用 LLM 或缺少生成上下文，已使用模板练习题。", null,
                            QUIZ_PROMPT_VERSION));
        }

        try {
            String raw = chatModel.chat(buildQuizPrompt(context));
            QuizNormalization normalized = normalizeQuizContent(raw, resource, knowledgePoint);
            if (!normalized.passed()) {
                return new GeneratedContent("json", fallback,
                        reviewResult("needs_review", "template_fallback", 62, normalized.checks(),
                                "LLM 输出未通过练习题结构校验，已使用模板内容降级。", preview(raw),
                                QUIZ_PROMPT_VERSION));
            }
            return new GeneratedContent("json", normalized.content(),
                    reviewResult("passed", "llm", 88, normalized.checks(),
                            "LLM 生成练习题通过结构校验，已规范化为前端可渲染 JSON。", null,
                            QUIZ_PROMPT_VERSION));
        } catch (Exception e) {
            log.warn("[ResourceContentGenerator] LLM quiz generation failed, fallback to template. resourceId={}",
                    resource.getId(), e);
            return new GeneratedContent("json", fallback,
                    reviewResult("needs_review", "template_fallback", 55,
                            List.of(check("llm_call_success", false), check("fallback_template", true)),
                            "LLM 调用或解析失败，已使用模板内容降级：" + e.getMessage(), null,
                            QUIZ_PROMPT_VERSION));
        }
    }

    private String buildDocPrompt(ResourceGenerationContext context) {
        return """
                你是严谨的学习资料生成 Agent。请根据上下文生成一份可直接学习的 Markdown 讲义。

                约束：
                - 只输出 Markdown，不要输出 JSON、代码围栏或额外解释。
                - 内容必须围绕知识点，不要泛泛而谈。
                - 必须包含这些二级标题：学习目标、核心概念、例题讲解、易错点、复盘问题。
                - 例题讲解至少包含 1 个具体例子，不能只写抽象步骤。
                - 语言要适合学生自学，避免空洞口号。

                学习空间：%s
                当前目标：%s
                当前任务：%s
                路径节点：%s
                知识点：%s
                使用场景：%s
                用户意图：%s
                目标学习时长：%d 分钟
                生成原因：%s

                请开始生成 Markdown 讲义。
                """.formatted(
                value(context.space() == null ? null : context.space().getName()),
                value(context.space() == null ? null : context.space().getGoals()),
                value(context.task() == null ? null : context.task().getTitle()),
                value(context.pathNode() == null ? null : context.pathNode().getTitle()),
                value(context.knowledgePoint()),
                value(context.studyScenario()),
                value(context.userIntent()),
                context.targetMinutes(),
                value(context.reason())
        );
    }

    private String buildQuizPrompt(ResourceGenerationContext context) {
        return """
                你是严谨的练习题生成 Agent。请根据学习上下文生成一组可直接用于学习空间的巩固练习。

                只输出一个 JSON 对象，不要输出 Markdown、代码围栏或额外解释。JSON 必须符合以下结构：
                {
                  "title": "练习标题",
                  "knowledgePoint": "知识点",
                  "questions": [
                    {
                      "type": "single_choice|multiple_choice|true_false|short_answer",
                      "stem": "题干",
                      "options": ["选项A", "选项B"],
                      "answer": "标准答案；多选用数组或用顿号分隔均可",
                      "referenceAnswer": "简答题参考答案",
                      "explanation": "解析",
                      "difficulty": 1,
                      "tags": ["知识点标签"]
                    }
                  ]
                }

                约束：
                - questions 数量为 5 到 8 题，至少包含 2 道选择题和 1 道简答题。
                - 所有题目必须围绕知识点和当前任务，不要泛泛考常识。
                - 选择题必须有 4 个选项，且答案必须能从选项中找到。
                - 判断题 options 固定为 ["正确","错误"]。
                - 简答题必须填写 referenceAnswer。
                - 每题必须有 explanation 和 difficulty，difficulty 为 1 到 5。
                - 不要生成含糊题干，例如“以下说法正确的是”后没有具体场景。

                学习空间：%s
                当前目标：%s
                当前任务：%s
                路径节点：%s
                知识点：%s
                使用场景：%s
                用户意图：%s
                目标练习时长：%d 分钟
                生成原因：%s
                """.formatted(
                value(context.space() == null ? null : context.space().getName()),
                value(context.space() == null ? null : context.space().getGoals()),
                value(context.task() == null ? null : context.task().getTitle()),
                value(context.pathNode() == null ? null : context.pathNode().getTitle()),
                value(context.knowledgePoint()),
                value(context.studyScenario()),
                value(context.userIntent()),
                context.targetMinutes(),
                value(context.reason())
        );
    }

    private List<Map<String, Object>> validateDocContent(String content, String knowledgePoint) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("non_empty", content != null && content.trim().length() >= 300));
        checks.add(check("markdown_heading", content != null && content.trim().startsWith("#")));
        checks.add(check("has_learning_goals", containsHeading(content, "学习目标")));
        checks.add(check("has_core_concepts", containsHeading(content, "核心概念")));
        checks.add(check("has_example", containsHeading(content, "例题讲解") || containsHeading(content, "例题")));
        checks.add(check("has_review_questions", containsHeading(content, "复盘问题")));
        checks.add(check("knowledge_point_aligned", content != null && content.contains(knowledgePoint)));
        return checks;
    }

    private boolean containsHeading(String content, String heading) {
        return content != null && (content.contains("## " + heading) || content.contains("### " + heading));
    }

    private String sanitizeMarkdown(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence).trim();
            }
        }
        return trimmed;
    }

    private GeneratedContent templateGeneratedContent(String contentType, String content) {
        return new GeneratedContent(contentType, content, reviewResult(
                "passed",
                "template",
                70,
                List.of(check("non_empty", content != null && !content.isBlank()), check("type_matches_content", true)),
                "MVP 模板内容格式校验通过，后续可接入对应类型 LLM 生成器。",
                null
        ));
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

    private QuizNormalization normalizeQuizContent(String raw, Resource resource, String knowledgePoint) {
        List<Map<String, Object>> checks = new ArrayList<>();
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(extractJsonObject(raw));
            checks.add(check("json_parse_success", true));
        } catch (Exception e) {
            checks.add(check("json_parse_success", false));
            checks.add(check("question_count_valid", false));
            return new QuizNormalization(null, checks, false);
        }

        if (root == null || !root.isObject()) {
            checks.add(check("json_object", false));
            checks.add(check("question_count_valid", false));
            return new QuizNormalization(null, checks, false);
        }
        checks.add(check("json_object", true));

        JsonNode questionsNode = root.get("questions");
        boolean questionsArray = questionsNode != null && questionsNode.isArray();
        checks.add(check("questions_array", questionsArray));
        if (!questionsArray) {
            checks.add(check("question_count_valid", false));
            return new QuizNormalization(null, checks, false);
        }

        List<Map<String, Object>> questions = new ArrayList<>();
        int invalidCount = 0;
        int choiceCount = 0;
        int shortAnswerCount = 0;
        for (JsonNode questionNode : questionsNode) {
            if (questions.size() >= MAX_QUIZ_QUESTION_COUNT) {
                break;
            }
            Map<String, Object> normalized = normalizeQuestion(questionNode);
            if (normalized == null) {
                invalidCount++;
                continue;
            }
            String type = String.valueOf(normalized.get("type"));
            if ("single_choice".equals(type) || "multiple_choice".equals(type)) {
                choiceCount++;
            }
            if ("short_answer".equals(type)) {
                shortAnswerCount++;
            }
            questions.add(normalized);
        }

        checks.add(check("question_count_valid", questions.size() >= MIN_QUIZ_QUESTION_COUNT));
        checks.add(check("invalid_questions_filtered", invalidCount == 0));
        checks.add(check("has_choice_questions", choiceCount >= 1));
        checks.add(check("has_short_answer", shortAnswerCount >= 1));
        checks.add(check("knowledge_point_present", containsKnowledgePoint(root, questions, knowledgePoint)));

        boolean passed = checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
        if (!passed) {
            return new QuizNormalization(null, checks, false);
        }

        Map<String, Object> normalizedRoot = new LinkedHashMap<>();
        normalizedRoot.put("title", firstNonBlank(text(root, "title"), resource.getTitle(), "专项练习：" + knowledgePoint));
        normalizedRoot.put("knowledgePoint", firstNonBlank(text(root, "knowledgePoint"), knowledgePoint));
        normalizedRoot.put("questions", questions);
        return new QuizNormalization(toJson(normalizedRoot), checks, true);
    }

    private Map<String, Object> normalizeQuestion(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String type = normalizeQuestionType(text(node, "type"));
        String stem = text(node, "stem");
        String explanation = text(node, "explanation");
        if (type == null || stem.length() < 8 || explanation.isBlank()) {
            return null;
        }

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", type);
        question.put("stem", stem);

        if ("single_choice".equals(type) || "multiple_choice".equals(type)) {
            List<String> options = stringList(node.get("options"), 2, 6);
            String answer = answerText(node.get("answer"));
            if (options.size() < 2 || answer.isBlank() || !answerMatchesOption(answer, options)) {
                return null;
            }
            question.put("options", options);
            question.put("answer", answer);
        } else if ("true_false".equals(type)) {
            String answer = answerText(node.get("answer"));
            if (!"正确".equals(answer) && !"错误".equals(answer)) {
                return null;
            }
            question.put("options", List.of("正确", "错误"));
            question.put("answer", answer);
        } else if ("short_answer".equals(type)) {
            String referenceAnswer = firstNonBlank(text(node, "referenceAnswer"), answerText(node.get("answer")));
            if (referenceAnswer.length() < 8) {
                return null;
            }
            question.put("referenceAnswer", referenceAnswer);
        }

        question.put("explanation", explanation);
        question.put("difficulty", normalizeDifficulty(node.get("difficulty")));
        List<String> tags = stringList(node.get("tags"), 0, 5);
        if (!tags.isEmpty()) {
            question.put("tags", tags);
        }
        return question;
    }

    private String extractJsonObject(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int lastFence = content.lastIndexOf("```");
            if (lastFence >= 0) {
                content = content.substring(0, lastFence).trim();
            }
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("LLM 输出中未找到 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    private String normalizeQuestionType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase()
                .replace("-", "_")
                .replace(" ", "_");
        return switch (type) {
            case "single", "single_choice", "choice" -> "single_choice";
            case "multiple", "multiple_choice", "multi_choice" -> "multiple_choice";
            case "true_false", "judge", "judgement", "判断题" -> "true_false";
            case "short", "short_answer", "essay", "简答题" -> "short_answer";
            default -> null;
        };
    }

    private List<String> stringList(JsonNode node, int min, int max) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText().trim() : item.toString().trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
            if (values.size() >= max) {
                break;
            }
        }
        return values.size() < min ? List.of() : values;
    }

    private String answerText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.isTextual() ? item.asText().trim() : item.toString().trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return String.join("、", values);
        }
        return node.isTextual() ? node.asText().trim() : node.toString().trim();
    }

    private boolean answerMatchesOption(String answer, List<String> options) {
        String normalizedAnswer = answer.trim();
        return options.stream().anyMatch(option -> {
            String normalizedOption = option.trim();
            return normalizedOption.equals(normalizedAnswer)
                    || normalizedAnswer.contains(normalizedOption)
                    || normalizedOption.startsWith(normalizedAnswer + ".")
                    || normalizedOption.startsWith(normalizedAnswer + "、")
                    || normalizedOption.startsWith(normalizedAnswer + "．");
        });
    }

    private int normalizeDifficulty(JsonNode node) {
        int difficulty = node != null && node.isNumber() ? node.asInt() : 3;
        return Math.max(1, Math.min(5, difficulty));
    }

    private boolean containsKnowledgePoint(JsonNode root, List<Map<String, Object>> questions, String knowledgePoint) {
        String expected = knowledgePoint == null ? "" : knowledgePoint.trim();
        if (expected.isBlank()) {
            return true;
        }
        if (text(root, "knowledgePoint").contains(expected)) {
            return true;
        }
        return questions.stream().anyMatch(question ->
                String.valueOf(question.get("stem")).contains(expected)
                        || String.valueOf(question.get("explanation")).contains(expected)
        );
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText().trim() : value.asText("").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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

    private String reviewResult(
            String status,
            String source,
            int score,
            List<Map<String, Object>> checks,
            String summary,
            String rejectedPreview
    ) {
        return reviewResult(status, source, score, checks, summary, rejectedPreview, DOC_PROMPT_VERSION);
    }

    private String reviewResult(
            String status,
            String source,
            int score,
            List<Map<String, Object>> checks,
            String summary,
            String rejectedPreview,
            String promptVersion
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("source", source);
        result.put("score", score);
        result.put("checks", checks);
        result.put("summary", summary);
        result.put("promptVersion", promptVersion);
        if (rejectedPreview != null && !rejectedPreview.isBlank()) {
            result.put("rejectedPreview", rejectedPreview);
        }
        return toJson(result);
    }

    private Map<String, Object> check(String name, boolean passed) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name);
        check.put("passed", passed);
        return check;
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 800 ? content : content.substring(0, 800) + "...";
    }

    private String value(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }

    private record GeneratedContent(String contentType, String content, String reviewResult) {
    }

    private record QuizNormalization(String content, List<Map<String, Object>> checks, boolean passed) {
    }
}
