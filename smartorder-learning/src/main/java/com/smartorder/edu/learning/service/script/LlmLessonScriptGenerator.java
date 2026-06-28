package com.smartorder.edu.learning.service.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.vo.StudyMasteryVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LlmLessonScriptGenerator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final LessonTimelineBuilder timelineBuilder;

    public StudySessionVO generate(LessonScriptContext context) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel bean not available");
        }

        String raw = chatModel.chat(buildPrompt(context));
        JsonNode root = parseJson(raw);
        List<StudyScriptStepVO> steps = parseSteps(context, root.path("steps"));
        if (steps.isEmpty()) {
            throw new IllegalStateException("LLM returned empty lesson steps");
        }

        int currentIndex = resolveStartIndex(steps, context.getRequestedStepId());
        steps.get(currentIndex).setStatus("in_progress");

        Map<String, StudyTimelineVO> timelinesByStepId = new LinkedHashMap<>();
        Map<String, StudySceneVO> scenesByStepId = new LinkedHashMap<>();
        JsonNode rawSteps = root.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            StudyScriptStepVO step = steps.get(i);
            timelinesByStepId.put(step.getId(), timelineBuilder.build(step));
            scenesByStepId.put(step.getId(), buildScene(step, rawSteps.path(i)));
        }

        StudySessionVO session = new StudySessionVO();
        session.setSessionId("ss-llm-" + safe(context.getTaskId(), "task"));
        session.setLessonBundleId("lb-llm-" + safe(context.getTaskId(), "task"));
        session.setUserId(context.getUserId());
        session.setTaskId(context.getTaskId());
        session.setSpaceId(context.getSpaceId());
        session.setMode(context.getMode());
        session.setStatus("active");
        session.setScriptSteps(steps);
        session.setCurrentStepId(steps.get(currentIndex).getId());
        session.setCurrentScene(scenesByStepId.get(session.getCurrentStepId()));
        session.setCurrentTimeline(timelinesByStepId.get(session.getCurrentStepId()));
        session.setScenesByStepId(scenesByStepId);
        session.setTimelinesByStepId(timelinesByStepId);
        session.setMastery(parseMastery(root.path("mastery")));
        return session;
    }

    private List<StudyScriptStepVO> parseSteps(LessonScriptContext context, JsonNode stepsNode) {
        if (!stepsNode.isArray()) {
            throw new IllegalStateException("LLM steps must be an array");
        }
        List<StudyScriptStepVO> steps = new ArrayList<>();
        int index = 1;
        for (JsonNode node : stepsNode) {
            String title = text(node, "title", "");
            if (title.isBlank()) {
                continue;
            }
            StudyScriptStepVO step = new StudyScriptStepVO();
            step.setId(safe(context.getTaskId(), "task") + "-llm-" + index);
            step.setPhase(text(node, "phase", index == 1 ? "intro" : "learning"));
            step.setNodeType(text(node, "nodeType", defaultNodeType(index)));
            step.setTitle(title);
            step.setSubtitle(text(node, "subtitle", ""));
            step.setEstimatedMinutes(clamp(intValue(node, "estimatedMinutes", 8), 3, 20));
            step.setStatus("pending");
            step.setCanvasType(normalizeCanvasType(text(node, "canvasType", defaultCanvasType(index))));
            step.setSortOrder(index);
            step.setMetadata(new LinkedHashMap<>(Map.of(
                    "generator", "llm",
                    "source", "llm_lesson_script_generator",
                    "contractVersion", 1,
                    "spaceName", safe(context.getSpaceName(), ""),
                    "pathNodeType", safe(context.getPathNodeType(), ""),
                    "difficultyLevel", context.getDifficultyLevel() == null ? 0 : context.getDifficultyLevel()
            )));
            steps.add(step);
            index++;
        }
        return steps;
    }

    private StudySceneVO buildScene(StudyScriptStepVO step, JsonNode rawStep) {
        StudySceneVO scene = new StudySceneVO();
        scene.setSceneId(step.getId());
        scene.setSceneType(step.getCanvasType());
        scene.setStepId(step.getId());

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", step.getCanvasType());
        content.put("markdown", text(rawStep, "markdown", defaultMarkdown(step)));
        content.put("teachingFlow", buildTeachingFlow(step, rawStep.path("teachingSegments")));
        content.put("metadata", new LinkedHashMap<>(Map.of(
                "source", "llm_lesson_script_generator",
                "nodeType", safe(step.getNodeType(), ""),
                "contractVersion", 1
        )));
        if (rawStep.has("exampleSteps")) {
            content.put("exampleSteps", toPlainObject(rawStep.path("exampleSteps")));
        }
        if (rawStep.has("quiz")) {
            content.put("question", normalizeQuiz(step, rawStep.path("quiz")));
        }
        scene.setContent(content);
        return scene;
    }

    private Map<String, Object> buildTeachingFlow(StudyScriptStepVO step, JsonNode segmentsNode) {
        List<Map<String, Object>> segments = new ArrayList<>();
        if (segmentsNode.isArray()) {
            int index = 1;
            for (JsonNode segmentNode : segmentsNode) {
                String markdown = text(segmentNode, "markdown", "");
                if (markdown.isBlank() && !"quiz".equals(text(segmentNode, "type", ""))) {
                    continue;
                }
                Map<String, Object> segment = new LinkedHashMap<>();
                String type = normalizeSegmentType(text(segmentNode, "type", "text"));
                segment.put("id", step.getId() + "-" + index);
                segment.put("type", type);
                segment.put("markdown", markdown);
                segment.put("autoAdvanceMs", "quiz".equals(type) || index >= segmentsNode.size() ? 0 : 3600 + index * 400);
                segment.put("agentCommentary", text(segmentNode, "agentCommentary", ""));
                if ("quiz".equals(type)) {
                    segment.put("quiz", normalizeQuiz(step, segmentNode.path("quiz")));
                }
                segments.add(segment);
                index++;
            }
        }
        if (segments.isEmpty()) {
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("id", step.getId() + "-1");
            segment.put("type", "text");
            segment.put("markdown", defaultMarkdown(step));
            segment.put("autoAdvanceMs", 0);
            segment.put("agentCommentary", "我们先抓住这一步的核心判断。");
            segments.add(segment);
        }
        return new LinkedHashMap<>(Map.of(
                "segments", segments,
                "currentSegmentIndex", 0,
                "isActive", false,
                "isPaused", false
        ));
    }

    private Map<String, Object> normalizeQuiz(StudyScriptStepVO step, JsonNode quizNode) {
        Map<String, Object> quiz = new LinkedHashMap<>();
        quiz.put("id", step.getId() + "-q1");
        quiz.put("prompt", text(quizNode, "prompt", "完成当前知识点的小检验"));
        quiz.put("answerType", text(quizNode, "answerType", "short_text"));
        quiz.put("body", text(quizNode, "body", text(quizNode, "prompt", "请说明这一步最关键的判断依据。")));
        quiz.put("answer", text(quizNode, "answer", text(quizNode, "correctAnswer", "")));
        quiz.put("correctAnswer", text(quizNode, "correctAnswer", text(quizNode, "answer", "")));
        quiz.put("hint", text(quizNode, "hint", "先回到本步骤的定义和适用条件。"));
        quiz.put("explanation", text(quizNode, "explanation", text(quizNode, "errorExplanation", "关键是先确认条件，再选择方法。")));
        if (quizNode.has("options")) {
            quiz.put("options", toPlainObject(quizNode.path("options")));
        }
        return quiz;
    }

    private StudyMasteryVO parseMastery(JsonNode node) {
        StudyMasteryVO mastery = new StudyMasteryVO();
        mastery.setLevel(text(node, "level", "progressing"));
        mastery.setLevelLabel(text(node, "levelLabel", "初步掌握"));
        mastery.setHint(text(node, "hint", "本脚本由 LLM 根据当前知识点生成，学习中会继续根据反馈调整。"));
        mastery.setNextSuggestion(text(node, "nextSuggestion", "完成本次学习后，根据小测结果安排复习或进入下一个知识点。"));
        return mastery;
    }

    private String buildPrompt(LessonScriptContext context) {
        return """
                你是一个严谨的中文学习教程脚本生成器。请根据学习上下文，为一个知识点生成可直接用于学习空间的教程脚本。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                输出结构：
                {
                  "steps": [
                    {
                      "phase": "review|concept|example|practice|assessment|summary",
                      "nodeType": "concept_review|worked_example|guided_practice|diagnostic_quiz|summary",
                      "title": "步骤标题",
                      "subtitle": "一句话说明",
                      "estimatedMinutes": 3-20,
                      "canvasType": "concept|example|practice|assessment|derivation|summary",
                      "markdown": "本步骤展示给学生的 Markdown 内容",
                      "teachingSegments": [
                        {"type": "text|formula|diagram|quiz", "markdown": "分段讲解", "agentCommentary": "导师口吻提示", "quiz": {"prompt": "题干", "answerType": "short_text", "answer": "参考答案", "hint": "提示", "explanation": "解析"}}
                      ],
                      "quiz": {"prompt": "题干", "answerType": "short_text", "answer": "参考答案", "hint": "提示", "explanation": "解析"},
                      "exampleSteps": [{"id": "ex1", "label": "步骤名", "markdown": "例题步骤"}]
                    }
                  ],
                  "mastery": {"level": "unknown|weak|progressing|mastered", "levelLabel": "中文标签", "hint": "学习判断", "nextSuggestion": "下一步建议"}
                }

                规则：
                1. steps 数量 4 到 6 个，必须包含概念、例题或练习、检验、总结。
                2. 内容必须围绕上下文知识点，不要使用“洛必达法则”等无关默认示例。
                3. 讲解要像教程脚本，不要只列大纲；每个 markdown 至少 80 个中文字符。
                4. quiz 要有明确参考答案和解析。
                5. 如果知识点属于编程/工程/计算机，请优先给出可执行思路、例子或伪代码。

                学习上下文：
                - 学习空间：%s
                - 空间类型：%s
                - 任务标题：%s
                - 任务描述：%s
                - 知识点标题：%s
                - 知识点描述：%s
                - 知识点类型：%s
                - 学习模式：%s
                - 难度等级：%s
                - 预计时长：%s 分钟
                """.formatted(
                safe(context.getSpaceName(), "未命名学习空间"),
                safe(context.getSpaceType(), ""),
                safe(context.getTaskTitle(), ""),
                safe(context.getTaskDescription(), ""),
                safe(topic(context), "当前知识点"),
                safe(context.getPathNodeDescription(), ""),
                safe(context.getPathNodeType(), ""),
                safe(context.getMode(), "learn"),
                context.getDifficultyLevel() == null ? "未知" : context.getDifficultyLevel(),
                context.getEstimatedDurationMinutes() == null ? "20" : context.getEstimatedDurationMinutes()
        );
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }
        String content = raw.trim();
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("LLM response does not contain JSON object");
        }
        return content.substring(start, end + 1);
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(extractJson(raw));
        } catch (Exception e) {
            throw new IllegalStateException("LLM response JSON parse failed: " + e.getMessage(), e);
        }
    }

    private Object toPlainObject(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private String topic(LessonScriptContext context) {
        if (context.getPathNodeTitle() != null && !context.getPathNodeTitle().isBlank()) {
            return context.getPathNodeTitle();
        }
        if (context.getTaskTitle() != null && !context.getTaskTitle().isBlank()) {
            return context.getTaskTitle();
        }
        return "当前知识点";
    }

    private int resolveStartIndex(List<StudyScriptStepVO> steps, String requestedStepId) {
        if (requestedStepId != null && !requestedStepId.isBlank()) {
            for (int i = 0; i < steps.size(); i++) {
                if (requestedStepId.equals(steps.get(i).getId())) {
                    return i;
                }
            }
        }
        return 0;
    }

    private String defaultMarkdown(StudyScriptStepVO step) {
        return "# " + safe(step.getTitle(), "学习步骤") + "\n\n"
                + safe(step.getSubtitle(), "先理解核心概念，再通过练习确认掌握。");
    }

    private String defaultNodeType(int index) {
        return switch (index) {
            case 1 -> "concept_review";
            case 2 -> "worked_example";
            case 3 -> "guided_practice";
            case 4 -> "diagnostic_quiz";
            default -> "summary";
        };
    }

    private String defaultCanvasType(int index) {
        return switch (index) {
            case 1 -> "concept";
            case 2 -> "example";
            case 3 -> "practice";
            case 4 -> "assessment";
            default -> "summary";
        };
    }

    private String normalizeCanvasType(String value) {
        return switch (safe(value, "").toLowerCase()) {
            case "concept", "example", "practice", "assessment", "derivation", "summary" -> value.toLowerCase();
            default -> "concept";
        };
    }

    private String normalizeSegmentType(String value) {
        return switch (safe(value, "").toLowerCase()) {
            case "text", "formula", "diagram", "quiz" -> value.toLowerCase();
            default -> "text";
        };
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
