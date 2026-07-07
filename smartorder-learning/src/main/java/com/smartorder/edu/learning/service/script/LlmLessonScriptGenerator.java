package com.smartorder.edu.learning.service.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.vo.StudyMasteryVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LlmLessonScriptGenerator {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern INLINE_OPTION_PATTERN = Pattern.compile(
            "(?s)(^|\\s)([A-Ha-h])\\s*[\\.、．)]\\s*(.+?)(?=(\\s+[A-Ha-h]\\s*[\\.、．)]\\s*)|$)");

    private final ChatModel chatModel;
    private final LessonTimelineBuilder timelineBuilder;

    public LlmLessonScriptGenerator(@Qualifier("lessonScriptChatModel") ChatModel chatModel,
                                    LessonTimelineBuilder timelineBuilder) {
        this.chatModel = chatModel;
        this.timelineBuilder = timelineBuilder;
    }

    public StudySessionVO generate(LessonScriptContext context) {
        String raw = chatModel.chat(buildPrompt(context));
        JsonNode root = parseAndValidateOrRepair(context, raw);
        return buildSession(context, root);
    }

    private StudySessionVO buildSession(LessonScriptContext context, JsonNode root) {
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
            StudySceneVO scene = buildScene(step, rawSteps.path(i));
            scenesByStepId.put(step.getId(), scene);
            timelinesByStepId.put(step.getId(), timelineBuilder.build(step, segmentTypesFromScene(scene)));
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

    private JsonNode parseAndValidateOrRepair(LessonScriptContext context, String raw) {
        List<String> errors;
        try {
            JsonNode root = parseJson(raw);
            errors = validateRoot(root);
            if (errors.isEmpty()) {
                return root;
            }
        } catch (Exception e) {
            errors = List.of("LLM response JSON parse failed: " + e.getMessage());
        }

        String repaired = chatModel.chat(buildRepairPrompt(context, raw, errors));
        JsonNode repairedRoot = parseJson(repaired);
        List<String> repairedErrors = validateRoot(repairedRoot);
        if (!repairedErrors.isEmpty()) {
            throw new IllegalStateException("LLM lesson script contract invalid after repair: "
                    + String.join("; ", repairedErrors));
        }
        return repairedRoot;
    }

    private List<String> validateRoot(JsonNode root) {
        List<String> errors = new ArrayList<>();
        JsonNode steps = root == null ? null : root.path("steps");
        if (steps == null || !steps.isArray()) {
            errors.add("steps must be an array");
            return errors;
        }
        int size = steps.size();
        if (size < 4 || size > 6) {
            errors.add("steps size must be 4 to 6, actual=" + size);
        }

        boolean hasConcept = false;
        boolean hasExampleOrPractice = false;
        boolean hasAssessment = false;
        boolean hasSummary = false;
        boolean hasQuiz = false;
        int index = 0;
        for (JsonNode step : steps) {
            index++;
            String title = text(step, "title", "");
            String markdown = text(step, "markdown", "");
            String canvasType = normalizeCanvasType(text(step, "canvasType", defaultCanvasType(index)));
            String phase = text(step, "phase", "");
            if (title.isBlank()) {
                errors.add("step " + index + " title is blank");
            }
            if (markdown.length() < 40) {
                errors.add("step " + index + " markdown is too short");
            }
            hasConcept = hasConcept || "concept".equals(canvasType) || "derivation".equals(canvasType);
            hasExampleOrPractice = hasExampleOrPractice || "example".equals(canvasType) || "practice".equals(canvasType);
            hasAssessment = hasAssessment || "assessment".equals(canvasType) || "assessment".equals(phase);
            hasSummary = hasSummary || "summary".equals(canvasType) || "summary".equals(phase);
            hasQuiz = hasQuiz || step.has("quiz") || containsSegmentQuiz(step.path("teachingSegments"));
        }
        if (!hasConcept) {
            errors.add("steps must include a concept or derivation step");
        }
        if (!hasExampleOrPractice) {
            errors.add("steps must include an example or practice step");
        }
        if (!hasAssessment) {
            errors.add("steps must include an assessment step");
        }
        if (!hasSummary) {
            errors.add("steps must include a summary step");
        }
        if (!hasQuiz) {
            errors.add("at least one quiz is required");
        }
        return errors;
    }

    private boolean containsSegmentQuiz(JsonNode segmentsNode) {
        if (!segmentsNode.isArray()) {
            return false;
        }
        for (JsonNode segment : segmentsNode) {
            if ("quiz".equals(normalizeSegmentType(text(segment, "type", ""))) || segment.has("quiz")) {
                return true;
            }
        }
        return false;
    }

    private List<StudyScriptStepVO> parseSteps(LessonScriptContext context, JsonNode stepsNode) {
        if (!stepsNode.isArray()) {
            throw new IllegalStateException("LLM steps must be an array");
        }
        List<StudyScriptStepVO> steps = new ArrayList<>();
        int index = 1;
        for (JsonNode node : stepsNode) {
            if (index > 6) {
                break;
            }
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

    @SuppressWarnings("unchecked")
    private List<String> segmentTypesFromScene(StudySceneVO scene) {
        if (scene == null || scene.getContent() == null) {
            return List.of();
        }
        Object flow = scene.getContent().get("teachingFlow");
        if (!(flow instanceof Map<?, ?> flowMap)) {
            return List.of();
        }
        Object segments = flowMap.get("segments");
        if (!(segments instanceof List<?> segmentList)) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (Object item : segmentList) {
            if (item instanceof Map<?, ?> segment) {
                Object rawType = segment.get("type");
                String type = normalizeSegmentType(rawType == null ? "text" : String.valueOf(rawType));
                types.add(type);
            }
        }
        return types;
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
        String rawPrompt = text(quizNode, "prompt", "完成当前知识点的小检验");
        String rawBody = text(quizNode, "body", rawPrompt);
        List<Map<String, Object>> options = normalizeOptions(quizNode.path("options"), rawPrompt, rawBody);
        String answerType = normalizeAnswerType(text(quizNode, "answerType", "text"), quizNode);
        String answer = text(quizNode, "answer", text(quizNode, "correctAnswer", "请参考解析"));
        String correctAnswer = text(quizNode, "correctAnswer", answer);
        String explanation = text(quizNode, "explanation",
                text(quizNode, "errorExplanation", "关键是先确认条件，再选择方法。"));
        if ("text".equals(answerType) && !options.isEmpty()) {
            answerType = "single_select";
        }
        if (("single_select".equals(answerType) || "multi_select".equals(answerType)) && options.isEmpty()) {
            answerType = "text";
        }
        if (!options.isEmpty()) {
            answer = normalizeOptionAnswer(answer, options, "multi_select".equals(answerType));
            correctAnswer = normalizeOptionAnswer(correctAnswer, options, "multi_select".equals(answerType));
            rawPrompt = stripInlineOptions(rawPrompt);
            rawBody = stripInlineOptions(rawBody);
        }
        quiz.put("id", step.getId() + "-q1");
        quiz.put("prompt", rawPrompt);
        quiz.put("answerType", answerType);
        quiz.put("body", rawBody.isBlank() ? rawPrompt : rawBody);
        quiz.put("answer", answer);
        quiz.put("correctAnswer", correctAnswer);
        quiz.put("hint", text(quizNode, "hint", "先回到本步骤的定义和适用条件。"));
        quiz.put("explanation", explanation);
        quiz.put("errorExplanation", explanation);
        if (!options.isEmpty() && !"text".equals(answerType)) {
            quiz.put("options", options);
        }
        return quiz;
    }

    private List<Map<String, Object>> normalizeOptions(JsonNode optionsNode, String prompt, String body) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (optionsNode != null && optionsNode.isArray()) {
            int index = 0;
            for (JsonNode optionNode : optionsNode) {
                Map<String, Object> option = normalizeOption(optionNode, index);
                if (option != null) {
                    options.add(option);
                    index++;
                }
            }
        }
        if (!options.isEmpty()) {
            return options;
        }
        options.addAll(parseInlineOptions(prompt));
        if (options.isEmpty() && body != null && !body.equals(prompt)) {
            options.addAll(parseInlineOptions(body));
        }
        return options;
    }

    private Map<String, Object> normalizeOption(JsonNode optionNode, int index) {
        String defaultValue = String.valueOf((char) ('a' + index));
        if (optionNode == null || optionNode.isNull()) {
            return null;
        }
        if (optionNode.isTextual()) {
            String label = optionNode.asText();
            return label == null || label.isBlank() ? null : option(defaultValue, label);
        }
        if (!optionNode.isObject()) {
            return null;
        }
        String value = text(optionNode, "value", text(optionNode, "key", defaultValue));
        String label = text(optionNode, "label", text(optionNode, "text", text(optionNode, "content", "")));
        if (label.isBlank()) {
            return null;
        }
        return option(normalizeOptionValue(value), stripOptionPrefix(label));
    }

    private List<Map<String, Object>> parseInlineOptions(String text) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return options;
        }
        Matcher matcher = INLINE_OPTION_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = normalizeOptionValue(matcher.group(2));
            String label = stripOptionPrefix(matcher.group(3).trim());
            if (!label.isBlank()) {
                options.add(option(value, label));
            }
        }
        return options.size() >= 2 ? options : List.of();
    }

    private Map<String, Object> option(String value, String label) {
        return new LinkedHashMap<>(Map.of("value", normalizeOptionValue(value), "label", label));
    }

    private String stripInlineOptions(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = INLINE_OPTION_PATTERN.matcher(text);
        return matcher.find() ? text.substring(0, matcher.start()).trim() : text;
    }

    private String stripOptionPrefix(String value) {
        return value == null ? "" : value.replaceFirst("^[A-Ha-h]\\s*[\\.、．)]\\s*", "").trim();
    }

    private String normalizeOptionValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() == 1 && Character.isLetter(trimmed.charAt(0))
                ? trimmed.toLowerCase()
                : trimmed;
    }

    private String normalizeOptionAnswer(String answer, List<Map<String, Object>> options, boolean multiSelect) {
        if (answer == null || answer.isBlank() || options.isEmpty()) {
            return answer;
        }
        if (multiSelect) {
            String[] parts = answer.split("[,，、\\s]+");
            List<String> normalized = new ArrayList<>();
            for (String part : parts) {
                String value = normalizeSingleOptionAnswer(part, options);
                if (value != null && !value.isBlank()) {
                    normalized.add(value);
                }
            }
            return normalized.isEmpty() ? answer : String.join(",", normalized);
        }
        String normalized = normalizeSingleOptionAnswer(answer, options);
        return normalized == null || normalized.isBlank() ? answer : normalized;
    }

    private String normalizeSingleOptionAnswer(String answer, List<Map<String, Object>> options) {
        String normalized = normalizeOptionValue(stripOptionPrefix(answer));
        for (Map<String, Object> option : options) {
            String value = String.valueOf(option.get("value"));
            String label = String.valueOf(option.get("label"));
            if (value.equals(normalized)
                    || label.equals(answer.trim())
                    || label.equals(stripOptionPrefix(answer))) {
                return value;
            }
        }
        Matcher matcher = INLINE_OPTION_PATTERN.matcher(answer.trim());
        if (matcher.find()) {
            return normalizeOptionValue(matcher.group(2));
        }
        return normalized.length() == 1 ? normalized : null;
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
                        {"type": "text|formula|diagram|quiz", "markdown": "分段讲解", "agentCommentary": "导师口吻提示", "quiz": {"prompt": "题干，不要把 A/B/C 选项写在这里", "answerType": "text|single_select|multi_select|number", "options": [{"label": "展示给学生的选项文本", "value": "a"}], "answer": "参考答案或选项 value", "correctAnswer": "参考答案或选项 value", "hint": "提示", "explanation": "解析"}}
                      ],
                      "quiz": {"prompt": "题干，不要把 A/B/C 选项写在这里", "answerType": "text|single_select|multi_select|number", "options": [{"label": "展示给学生的选项文本", "value": "a"}], "answer": "参考答案或选项 value", "correctAnswer": "参考答案或选项 value", "hint": "提示", "explanation": "解析"},
                      "exampleSteps": [{"id": "ex1", "label": "步骤名", "markdown": "例题步骤"}]
                    }
                  ],
                  "mastery": {"level": "unknown|weak|progressing|mastered", "levelLabel": "中文标签", "hint": "学习判断", "nextSuggestion": "下一步建议"}
                }

                规则：
                1. steps 数量 4 到 6 个，必须包含概念、例题或练习、检验、总结。
                2. 内容必须围绕上下文知识点，不要使用“洛必达法则”等无关默认示例。
                3. 讲解要像教程脚本，不要只列大纲；每个 markdown 至少 80 个中文字符。
                4. quiz 要有明确参考答案和解析；选择题必须使用 single_select 或 multi_select，并提供 options 数组。
                5. answerType 只能使用 text、single_select、multi_select、number；不要使用 short_text。
                6. 选择题的 options 必须是 {"label":"展示给学生的选项文本","value":"a|b|c|d"}，correctAnswer 必须填写选项 value，不要把 “A. ... B. ...” 拼进 prompt。
                7. 如果知识点属于编程/工程/计算机，请优先给出可执行思路、例子或伪代码。

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

    private String buildRepairPrompt(LessonScriptContext context, String invalidJson, List<String> errors) {
        return """
                你是学习教程脚本 JSON 修复器。请根据错误列表修复下方 JSON，使其严格满足学习空间教程脚本合同。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                必须满足：
                - steps 数量为 4 到 6。
                - 必须包含 concept 或 derivation 步骤。
                - 必须包含 example 或 practice 步骤。
                - 必须包含 assessment 步骤，且至少有一个 quiz。
                - 必须包含 summary 步骤。
                - 每个 step 必须有 title、subtitle、estimatedMinutes、canvasType、markdown、teachingSegments。
                - canvasType 只能是 concept、example、practice、assessment、derivation、summary。
                - teachingSegments[*].type 只能是 text、formula、diagram、quiz。
                - quiz.answerType 只能是 text、single_select、multi_select、number。
                - 当 quiz.answerType 为 single_select 或 multi_select 时，quiz.options 必须是数组，且每项包含 label 和 value；correctAnswer 必须填写选项 value。
                - answer 和 correctAnswer 都必须有值。
                - 内容必须围绕知识点，不要使用无关默认示例。

                学习上下文：
                - 学习空间：%s
                - 任务标题：%s
                - 知识点标题：%s
                - 知识点描述：%s
                - 学习模式：%s

                错误列表：
                %s

                待修复 JSON：
                %s
                """.formatted(
                safe(context.getSpaceName(), "未命名学习空间"),
                safe(context.getTaskTitle(), ""),
                safe(topic(context), "当前知识点"),
                safe(context.getPathNodeDescription(), ""),
                safe(context.getMode(), "learn"),
                "- " + String.join("\n- ", errors),
                invalidJson == null ? "" : invalidJson
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

    private String normalizeAnswerType(String value, JsonNode quizNode) {
        String normalized = safe(value, "text").toLowerCase().replace('-', '_');
        String answerType = switch (normalized) {
            case "single_select", "single_choice", "choice", "select" -> "single_select";
            case "multi_select", "multi_choice", "multiple_choice" -> "multi_select";
            case "number", "numeric" -> "number";
            case "text", "short_text", "short_answer", "free_text", "open_text" -> "text";
            default -> "text";
        };
        if (("single_select".equals(answerType) || "multi_select".equals(answerType))
                && !quizNode.path("options").isArray()) {
            return "text";
        }
        return answerType;
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
