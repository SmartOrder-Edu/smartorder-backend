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
            timelinesByStepId.put(step.getId(), timelineBuilder.build(step, scene));
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
            String teacherTalk = collectSegmentField(step.path("teachingSegments"), "teacherTalk");
            String boardContent = collectSegmentField(step.path("teachingSegments"), "boardContent");
            String boardBlocks = collectSegmentBoardBlocks(step.path("teachingSegments"));
            String instructionalText = markdown + teacherTalk + boardContent + boardBlocks;
            String canvasType = normalizeCanvasType(text(step, "canvasType", defaultCanvasType(index)));
            String phase = text(step, "phase", "");
            if (title.isBlank()) {
                errors.add("step " + index + " title is blank");
            }
            if (instructionalText.length() < 80) {
                errors.add("step " + index + " instructional content is too short");
            }
            hasConcept = hasConcept || "concept".equals(canvasType) || "derivation".equals(canvasType);
            hasExampleOrPractice = hasExampleOrPractice || "example".equals(canvasType) || "practice".equals(canvasType);
            hasAssessment = hasAssessment || "assessment".equals(canvasType) || "assessment".equals(phase);
            hasSummary = hasSummary || "summary".equals(canvasType) || "summary".equals(phase);
            hasQuiz = hasQuiz || step.has("quiz") || containsSegmentQuiz(step.path("teachingSegments"));
            errors.addAll(validateTeachingSegments(step.path("teachingSegments"), index));
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

    private List<String> validateTeachingSegments(JsonNode segmentsNode, int stepIndex) {
        List<String> errors = new ArrayList<>();
        if (!segmentsNode.isArray() || segmentsNode.isEmpty()) {
            errors.add("step " + stepIndex + " teachingSegments must be a non-empty array");
            return errors;
        }
        int segmentIndex = 0;
        for (JsonNode segment : segmentsNode) {
            segmentIndex++;
            boolean hasBasicContent = !text(segment, "teacherTalk", "").isBlank()
                    || !text(segment, "boardContent", "").isBlank()
                    || !text(segment, "markdown", "").isBlank();
            boolean hasBoardBlocks = hasNonEmptyBoardBlocks(segment.path("boardBlocks"));
            if (!hasBasicContent && !hasBoardBlocks && !isInteractiveSegment(normalizeSegmentType(text(segment, "type", "")))) {
                errors.add("step " + stepIndex + " segment " + segmentIndex
                        + " must have teacherTalk, boardBlocks, boardContent or markdown");
            }

            JsonNode blocks = segment.path("boardBlocks");
            if (blocks.isMissingNode() || blocks.isNull()) {
                continue;
            }
            if (!blocks.isArray()) {
                errors.add("step " + stepIndex + " segment " + segmentIndex + " boardBlocks must be an array");
                continue;
            }
            int blockIndex = 0;
            for (JsonNode block : blocks) {
                blockIndex++;
                if (!block.isObject()) {
                    errors.add("step " + stepIndex + " segment " + segmentIndex
                            + " boardBlocks[" + blockIndex + "] must be an object");
                    continue;
                }
                String type = text(block, "type", "markdown").toLowerCase();
                String content = text(block, "content", "");
                if (!isAllowedBoardBlockType(type)) {
                    errors.add("step " + stepIndex + " segment " + segmentIndex
                            + " boardBlocks[" + blockIndex + "] type is invalid: " + type);
                }
                if (!hasMeaningfulBoardBlock(block)) {
                    errors.add("step " + stepIndex + " segment " + segmentIndex
                            + " boardBlocks[" + blockIndex + "] content or structured fields are blank");
                }
                if ("code".equals(type) && text(block, "language", "").isBlank()) {
                    errors.add("step " + stepIndex + " segment " + segmentIndex
                            + " boardBlocks[" + blockIndex + "] code language is required");
                }
                if ("code".equals(type) && content.isBlank()) {
                    errors.add("step " + stepIndex + " segment " + segmentIndex
                            + " boardBlocks[" + blockIndex + "] code content is required");
                }
                if ("structure".equals(type) && text(block, "structureType", "").isBlank()) {
                    errors.add("step " + stepIndex + " segment " + segmentIndex
                            + " boardBlocks[" + blockIndex + "] structureType is required");
                }
            }
        }
        return errors;
    }

    private boolean hasNonEmptyBoardBlocks(JsonNode blocksNode) {
        if (!blocksNode.isArray()) {
            return false;
        }
        for (JsonNode block : blocksNode) {
            if (hasMeaningfulBoardBlock(block)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedBoardBlockType(String type) {
        return switch (safe(type, "markdown").toLowerCase()) {
            case "markdown", "code", "formula", "list", "quote", "diagram", "callout",
                    "structure", "trace", "complexity" -> true;
            default -> false;
        };
    }

    private boolean hasMeaningfulBoardBlock(JsonNode block) {
        if (block == null || !block.isObject()) {
            return false;
        }
        String type = text(block, "type", "markdown").toLowerCase();
        if (!text(block, "content", "").isBlank()) {
            return true;
        }
        return switch (type) {
            case "structure" -> !text(block, "structureType", "").isBlank()
                    || nonEmptyArray(block.path("items"))
                    || nonEmptyArray(block.path("nodes"));
            case "trace" -> nonEmptyArray(block.path("items"))
                    || !text(block, "action", "").isBlank()
                    || !text(block, "explanation", "").isBlank();
            case "complexity" -> !text(block, "time", "").isBlank()
                    || !text(block, "space", "").isBlank()
                    || nonEmptyArray(block.path("cases"))
                    || !text(block, "reason", "").isBlank();
            default -> false;
        };
    }

    private boolean nonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty();
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

    private String collectSegmentField(JsonNode segmentsNode, String field) {
        if (!segmentsNode.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode segment : segmentsNode) {
            builder.append(text(segment, field, ""));
        }
        return builder.toString();
    }

    private String collectSegmentBoardBlocks(JsonNode segmentsNode) {
        if (!segmentsNode.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode segment : segmentsNode) {
            JsonNode blocks = segment.path("boardBlocks");
            if (!blocks.isArray()) {
                continue;
            }
            for (JsonNode block : blocks) {
                builder.append(text(block, "title", ""));
                builder.append(text(block, "content", ""));
                builder.append(text(block, "caption", ""));
                builder.append(text(block, "structureType", ""));
                builder.append(text(block, "action", ""));
                builder.append(text(block, "explanation", ""));
                builder.append(text(block, "time", ""));
                builder.append(text(block, "space", ""));
                builder.append(text(block, "reason", ""));
                if (block.has("items")) {
                    builder.append(block.path("items"));
                }
                if (block.has("nodes")) {
                    builder.append(block.path("nodes"));
                }
                if (block.has("cases")) {
                    builder.append(block.path("cases"));
                }
            }
        }
        return builder.toString();
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
                String type = normalizeSegmentType(text(segmentNode, "type", "explain"));
                String teacherTalk = text(segmentNode, "teacherTalk",
                        text(segmentNode, "agentCommentary", ""));
                String boardContent = text(segmentNode, "boardContent", "");
                String markdown = text(segmentNode, "markdown", "");
                List<Map<String, Object>> boardBlocks = normalizeBoardBlocks(segmentNode, boardContent, markdown, type);
                if (boardContent.isBlank() && !boardBlocks.isEmpty()) {
                    boardContent = boardBlocksToMarkdown(boardBlocks);
                }
                if (markdown.isBlank()) {
                    markdown = boardContent.isBlank() ? teacherTalk : boardContent;
                }
                if (markdown.isBlank() && !isInteractiveSegment(type)) {
                    continue;
                }
                Map<String, Object> segment = new LinkedHashMap<>();
                segment.put("id", step.getId() + "-" + index);
                segment.put("type", type);
                segment.put("markdown", markdown);
                segment.put("teacherTalk", teacherTalk.isBlank() ? markdown : teacherTalk);
                segment.put("boardContent", boardContent);
                segment.put("boardBlocks", boardBlocks);
                segment.put("teachingIntent", text(segmentNode, "teachingIntent", ""));
                segment.put("emotionTone", text(segmentNode, "emotionTone", defaultTone(type)));
                segment.put("agentCommentary", text(segmentNode, "agentCommentary",
                        teacherTalk.isBlank() ? defaultCommentary(type) : teacherTalk));
                if (segmentNode.has("visualHint")) {
                    segment.put("visualHint", toPlainObject(segmentNode.path("visualHint")));
                }
                if (segmentNode.has("interaction")) {
                    segment.put("interaction", toPlainObject(segmentNode.path("interaction")));
                }
                int autoAdvanceMs = resolveAutoAdvanceMs(segmentNode, type, index >= segmentsNode.size());
                segment.put("autoAdvanceMs", autoAdvanceMs);
                if (isInteractiveSegment(type)) {
                    JsonNode quizNode = segmentNode.has("quiz")
                            ? segmentNode.path("quiz")
                            : segmentNode.path("interaction");
                    segment.put("quiz", normalizeQuiz(step, quizNode));
                }
                segments.add(segment);
                index++;
            }
        }
        if (segments.isEmpty()) {
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("id", step.getId() + "-1");
            segment.put("type", "explain");
            segment.put("markdown", defaultMarkdown(step));
            segment.put("teacherTalk", "我们先抓住这一步的核心判断，再慢慢展开。");
            segment.put("boardContent", defaultMarkdown(step));
            segment.put("boardBlocks", List.of(boardBlock("markdown", "基础板书", null, defaultMarkdown(step), "info")));
            segment.put("teachingIntent", "兜底生成基础讲解段，保证课堂脚本可播放。");
            segment.put("emotionTone", "serious");
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

    private List<Map<String, Object>> normalizeBoardBlocks(JsonNode segmentNode,
                                                           String boardContent,
                                                           String markdown,
                                                           String segmentType) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        JsonNode blocksNode = segmentNode.path("boardBlocks");
        if (blocksNode.isArray()) {
            int index = 1;
            for (JsonNode blockNode : blocksNode) {
                Map<String, Object> block = normalizeBoardBlock(blockNode, index, segmentType);
                if (block != null) {
                    blocks.add(block);
                    index++;
                }
            }
        }
        if (!blocks.isEmpty()) {
            return blocks;
        }

        String fallback = boardContent.isBlank() ? markdown : boardContent;
        if (fallback == null || fallback.isBlank()) {
            return blocks;
        }
        String type = inferBoardBlockType(fallback, segmentType);
        String language = "code".equals(type) ? inferCodeLanguage(fallback) : null;
        String title = switch (type) {
            case "code" -> "示例代码";
            case "formula" -> "关键公式";
            case "diagram" -> "结构图";
            case "callout" -> "提示";
            default -> "板书";
        };
        blocks.add(boardBlock(type, title, language, stripMarkdownFence(fallback), defaultBoardTone(segmentType)));
        return blocks;
    }

    private Map<String, Object> normalizeBoardBlock(JsonNode blockNode, int index, String segmentType) {
        if (blockNode == null || !blockNode.isObject()) {
            return null;
        }
        String content = text(blockNode, "content", "");
        String type = normalizeBoardBlockType(text(blockNode, "type", inferBoardBlockType(content, segmentType)));
        if (!hasMeaningfulBoardBlock(blockNode) && content.isBlank()) {
            return null;
        }
        String language = text(blockNode, "language", "code".equals(type) ? inferCodeLanguage(content) : "");
        String title = text(blockNode, "title", defaultBoardBlockTitle(type));
        String tone = text(blockNode, "tone", defaultBoardTone(segmentType));
        String id = text(blockNode, "id", "board-block-" + index);
        Map<String, Object> block = boardBlock(type, title, language, content, tone, id);
        copyBoardBlockField(blockNode, block, "caption");
        copyBoardBlockField(blockNode, block, "highlights");
        copyBoardBlockField(blockNode, block, "focusLine");
        copyBoardBlockField(blockNode, block, "errorLines");
        copyBoardBlockField(blockNode, block, "noteLines");
        copyBoardBlockField(blockNode, block, "structureType");
        copyBoardBlockField(blockNode, block, "items");
        copyBoardBlockField(blockNode, block, "nodes");
        copyBoardBlockField(blockNode, block, "edges");
        copyBoardBlockField(blockNode, block, "pointers");
        copyBoardBlockField(blockNode, block, "activeIndices");
        copyBoardBlockField(blockNode, block, "activeIds");
        copyBoardBlockField(blockNode, block, "algorithm");
        copyBoardBlockField(blockNode, block, "processedRange");
        copyBoardBlockField(blockNode, block, "action");
        copyBoardBlockField(blockNode, block, "explanation");
        copyBoardBlockField(blockNode, block, "time");
        copyBoardBlockField(blockNode, block, "space");
        copyBoardBlockField(blockNode, block, "cases");
        copyBoardBlockField(blockNode, block, "reason");
        return block;
    }

    private void copyBoardBlockField(JsonNode source, Map<String, Object> target, String field) {
        JsonNode value = source.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isTextual() && value.asText().isBlank()) {
            return;
        }
        target.put(field, toPlainObject(value));
    }

    private Map<String, Object> boardBlock(String type, String title, String language, String content, String tone) {
        return boardBlock(type, title, language, content, tone, null);
    }

    private Map<String, Object> boardBlock(String type, String title, String language, String content, String tone, String id) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (id != null && !id.isBlank()) {
            block.put("id", id);
        }
        block.put("type", normalizeBoardBlockType(type));
        block.put("title", title == null ? "" : title);
        if (language != null && !language.isBlank()) {
            block.put("language", language);
        }
        if (content != null && !content.isBlank()) {
            block.put("content", stripMarkdownFence(content));
        }
        block.put("tone", tone == null || tone.isBlank() ? "info" : tone);
        return block;
    }

    private String boardBlocksToMarkdown(List<Map<String, Object>> blocks) {
        List<String> markdownBlocks = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            String type = String.valueOf(block.getOrDefault("type", "markdown"));
            String content = String.valueOf(block.getOrDefault("content", ""));
            String language = String.valueOf(block.getOrDefault("language", ""));
            if (content.isBlank()) {
                String summary = structuredBoardBlockSummary(block);
                if (!summary.isBlank()) {
                    markdownBlocks.add(summary);
                }
                continue;
            }
            if ("code".equals(type)) {
                markdownBlocks.add("```" + ("null".equals(language) ? "" : language) + "\n" + content + "\n```");
            } else if ("diagram".equals(type)) {
                markdownBlocks.add("```mermaid\n" + content + "\n```");
            } else {
                markdownBlocks.add(content);
            }
        }
        return String.join("\n\n", markdownBlocks);
    }

    private String structuredBoardBlockSummary(Map<String, Object> block) {
        String type = String.valueOf(block.getOrDefault("type", "markdown"));
        String title = String.valueOf(block.getOrDefault("title", ""));
        return switch (type) {
            case "structure" -> title + "\n\n"
                    + "- 类型：" + block.getOrDefault("structureType", "数据结构") + "\n"
                    + "- 元素：" + block.getOrDefault("items", block.getOrDefault("nodes", List.of())) + "\n"
                    + "- 指针：" + block.getOrDefault("pointers", Map.of());
            case "trace" -> title + "\n\n"
                    + "- 动作：" + block.getOrDefault("action", "标记") + "\n"
                    + "- 当前序列：" + block.getOrDefault("items", List.of()) + "\n"
                    + "- 说明：" + block.getOrDefault("explanation", "");
            case "complexity" -> title + "\n\n"
                    + "- 时间复杂度：" + block.getOrDefault("time", "待分析") + "\n"
                    + "- 空间复杂度：" + block.getOrDefault("space", "待分析") + "\n"
                    + "- 原因：" + block.getOrDefault("reason", "");
            default -> "";
        };
    }

    private String inferBoardBlockType(String content, String segmentType) {
        if ("formula".equals(segmentType) || content.contains("$$")) {
            return "formula";
        }
        if ("diagram".equals(segmentType) || content.contains("flowchart") || content.contains("sequenceDiagram")) {
            return "diagram";
        }
        if ("mistake_warning".equals(segmentType) || "diagnosis".equals(segmentType)) {
            return "callout";
        }
        if (isLikelyCode(content)) {
            return "code";
        }
        return "markdown";
    }

    private String normalizeBoardBlockType(String type) {
        return switch (safe(type, "markdown").toLowerCase()) {
            case "code", "formula", "list", "quote", "diagram", "callout",
                    "structure", "trace", "complexity" -> type.toLowerCase();
            default -> "markdown";
        };
    }

    private String defaultBoardBlockTitle(String type) {
        return switch (type) {
            case "code" -> "示例代码";
            case "formula" -> "关键公式";
            case "diagram" -> "结构图";
            case "callout" -> "提示";
            case "structure" -> "数据结构状态";
            case "trace" -> "算法过程";
            case "complexity" -> "复杂度分析";
            default -> "板书";
        };
    }

    private String defaultBoardTone(String segmentType) {
        return switch (segmentType) {
            case "mistake_warning", "diagnosis" -> "warning";
            case "summary" -> "success";
            default -> "info";
        };
    }

    private boolean isLikelyCode(String content) {
        String[] lines = content.split("\\R");
        int nonBlank = 0;
        int codeLines = 0;
        for (String line : lines) {
            String text = line.trim();
            if (text.isBlank()) {
                continue;
            }
            nonBlank++;
            if (text.matches(".*[;{}]$")
                    || text.matches("^(#include|import|package|typedef|struct|class|interface|enum|public|private|protected|function|def|const|let|var)\\b.*")
                    || text.matches("^(if|else|for|while|switch|case|return|try|catch)\\b.*")
                    || text.matches(".*(\\+\\+|--|->|=>|==|!=|<=|>=|&&|\\|\\|).*")) {
                codeLines++;
            }
        }
        return nonBlank >= 2 && (codeLines >= 3 || codeLines * 1.0 / nonBlank >= 0.45);
    }

    private String inferCodeLanguage(String content) {
        if (content.matches("(?s).*(#include\\s*<|typedef\\s+struct|->|int\\*|char\\*).*")) {
            return "c";
        }
        if (content.matches("(?s).*(\\bpublic\\s+class\\b|\\bSystem\\.out\\.println\\b).*")) {
            return "java";
        }
        if (content.matches("(?s).*(\\bdef\\s+\\w+\\(|\\bprint\\().*")) {
            return "python";
        }
        if (content.matches("(?s).*(\\bfunction\\s+\\w+\\(|\\bconst\\s+\\w+\\s*=|\\blet\\s+\\w+\\s*=|=>).*")) {
            return "javascript";
        }
        if (content.matches("(?s).*(\\binterface\\s+\\w+\\b|:\\s*(string|number|boolean)\\b).*")) {
            return "typescript";
        }
        if (content.matches("(?is).*(\\bSELECT\\b|\\bFROM\\b|\\bWHERE\\b).*")) {
            return "sql";
        }
        return "";
    }

    private String stripMarkdownFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        Matcher matcher = Pattern.compile("(?s)^```[a-zA-Z0-9_-]*\\s*(.*?)\\s*```$").matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return content;
    }

    private int resolveAutoAdvanceMs(JsonNode segmentNode, String type, boolean lastSegment) {
        JsonNode pacing = segmentNode.path("pacing");
        if (pacing != null && pacing.isObject()) {
            if (pacing.path("waitForUser").asBoolean(false)) {
                return 0;
            }
            JsonNode autoAdvance = pacing.path("autoAdvanceMs");
            if (autoAdvance.isNumber()) {
                return clamp(autoAdvance.asInt(), 0, 12000);
            }
        }
        return isInteractiveSegment(type) || lastSegment ? 0 : 3600;
    }

    private boolean isInteractiveSegment(String type) {
        return "quiz".equals(type)
                || "teacher_question".equals(type)
                || "student_try".equals(type)
                || "self_check".equals(type);
    }

    private String defaultTone(String type) {
        return switch (type) {
            case "hook", "analogy" -> "curious";
            case "mistake_warning", "diagnosis" -> "warning";
            case "summary" -> "summary";
            default -> "serious";
        };
    }

    private String defaultCommentary(String type) {
        return switch (type) {
            case "hook" -> "先看一个问题，把注意力放到今天要解决的矛盾上。";
            case "recall" -> "先回忆前置知识，后面会用到它。";
            case "teacher_question", "student_try", "self_check" -> "这里停一下，你先判断再继续。";
            case "mistake_warning" -> "这一点很容易错，先把错误原因看清楚。";
            case "summary" -> "最后把这节课的方法收束成可复用步骤。";
            default -> "继续推进这一步，注意老师强调的判断条件。";
        };
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
                text(quizNode, "feedbackWhenCorrect",
                        text(quizNode, "feedbackWhenWrong",
                                text(quizNode, "errorExplanation", "关键是先确认条件，再选择方法。"))));
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
        quiz.put("feedbackWhenCorrect", text(quizNode, "feedbackWhenCorrect", explanation));
        quiz.put("feedbackWhenWrong", text(quizNode, "feedbackWhenWrong", explanation));
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
                        {
                          "type": "hook|recall|explain|analogy|board_write|diagram|worked_example|teacher_question|student_try|diagnosis|mistake_warning|summary|transition|quiz|formula",
                          "teacherTalk": "老师真实会说出口的话，像面对学生讲课，不要写成资料摘要",
                          "boardContent": "兼容字段：黑板/画布上逐步出现的 Markdown 内容",
                          "boardBlocks": [
                            {"type": "markdown|code|formula|list|quote|diagram|callout|structure|trace|complexity", "title": "板书块标题", "content": "普通文本或代码纯内容", "caption": "简短说明", "tone": "info|warning|success|error"},
                            {"type": "code", "title": "代码标题", "language": "c|cpp|java|python|javascript|typescript|go|rust|sql", "content": "纯代码，不要包含 Markdown 代码围栏", "highlights": [2, 3], "focusLine": 3},
                            {"type": "structure", "structureType": "array|stack|queue|linked_list|tree|heap|graph", "title": "结构状态标题", "items": [1, 2, 3], "pointers": {"front": 0, "rear": 3, "top": 2}, "activeIndices": [0]},
                            {"type": "trace", "algorithm": "bubble_sort|binary_search|dfs|bfs|...", "title": "过程标题", "items": [5, 2, 8], "activeIndices": [0, 1], "processedRange": [2, 2], "action": "compare|swap|insert|delete|visit|mark", "explanation": "当前步骤解释"},
                            {"type": "complexity", "title": "复杂度标题", "time": "O(n)", "space": "O(1)", "cases": [{"label": "最坏情况", "value": "O(n)", "description": "原因"}], "reason": "复杂度原因"}
                          ],
                          "markdown": "兼容字段，通常等于 boardContent 或本段展示内容",
                          "teachingIntent": "说明本段为什么这样讲",
                          "emotionTone": "curious|encourage|serious|warning|summary",
                          "visualHint": {"type": "formula|diagram|code|table|mindmap|text", "content": "可视化内容"},
                          "interaction": {"type": "pause|single_select|multi_select|text_answer|predict|self_check", "prompt": "课堂提问", "options": [{"label": "选项文本", "value": "a"}], "correctAnswer": "a", "feedbackWhenCorrect": "答对反馈", "feedbackWhenWrong": "答错反馈"},
                          "pacing": {"autoAdvanceMs": 3600, "waitForUser": false}
                        }
                      ],
                      "quiz": {"prompt": "题干，不要把 A/B/C 选项写在这里", "answerType": "text|single_select|multi_select|number", "options": [{"label": "展示给学生的选项文本", "value": "a"}], "answer": "参考答案或选项 value", "correctAnswer": "参考答案或选项 value", "hint": "提示", "explanation": "解析"},
                      "exampleSteps": [{"id": "ex1", "label": "步骤名", "markdown": "例题步骤"}]
                    }
                  ],
                  "mastery": {"level": "unknown|weak|progressing|mastered", "levelLabel": "中文标签", "hint": "学习判断", "nextSuggestion": "下一步建议"}
                }

                规则：
                1. steps 数量 4 到 6 个，必须包含导入、前置回忆、概念、例题或练习、检验、总结。
                2. 内容必须围绕上下文知识点，不要使用“洛必达法则”等无关默认示例。
                3. 讲解要像老师正在上课，不要只列大纲；每个 step 的 teacherTalk + boardContent + boardBlocks.content 总量至少 160 个中文字符。
                4. quiz 要有明确参考答案和解析；选择题必须使用 single_select 或 multi_select，并提供 options 数组。
                5. answerType 只能使用 text、single_select、multi_select、number；不要使用 short_text。
                6. 选择题的 options 必须是 {"label":"展示给学生的选项文本","value":"a|b|c|d"}，correctAnswer 必须填写选项 value，不要把 “A. ... B. ...” 拼进 prompt。
                7. 如果知识点属于编程/工程/计算机，请优先给出可执行思路、例子或伪代码。
                8. 每个脚本至少包含 3 次课堂停顿或互动，类型可为 teacher_question、student_try、quiz 或 self_check。
                9. 至少包含 1 个 mistake_warning 或 diagnosis，像老师一样指出常见误区并纠正。
                10. teacherTalk 用自然口语，boardBlocks/boardContent 用精炼板书；不要把长篇资料一口气塞进同一段。
                11. 每个 teachingSegment 优先输出 boardBlocks。普通板书用 type=markdown；算法/程序代码必须用 type=code，并填写 language；公式用 type=formula；常见误区或提示用 type=callout。
                12. 如果知识点属于数据结构或算法，必须优先使用结构化板书：结构状态用 type=structure，排序/查找/遍历过程用 type=trace，复杂度结论用 type=complexity。
                13. structure 当前优先使用 structureType=array|stack|queue|linked_list，items 表示元素，pointers 表示 front/rear/top/head 等指针，activeIndices 表示当前高亮下标。
                14. trace 当前优先用 items、activeIndices、processedRange、action、explanation 表示算法执行过程。
                15. complexity 使用 time、space、cases、reason，不要把复杂度结论只写在普通段落里。
                16. boardBlocks[*].content 必须是纯内容：代码不要包 ```，Mermaid 图不要包 ```mermaid；前端会根据 type 和 language 负责渲染。

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
                - teachingSegments[*].type 只能是 hook、recall、explain、analogy、board_write、diagram、worked_example、teacher_question、student_try、diagnosis、mistake_warning、summary、transition、quiz、formula。
                - 每个 teachingSegment 必须有 teacherTalk、boardBlocks、boardContent 或 markdown 之一。
                - 每个 teachingSegment 应优先提供 boardBlocks；boardBlocks[*].type 只能是 markdown、code、formula、list、quote、diagram、callout、structure、trace、complexity。
                - 代码必须放入 {"type":"code","language":"c|java|python|javascript|...","content":"纯代码"}，content 不要包含 Markdown 代码围栏。
                - 数据结构状态必须优先使用 {"type":"structure","structureType":"array|stack|queue|linked_list|tree|heap|graph","items":[...],"pointers":{...},"activeIndices":[...]}。
                - 算法推演必须优先使用 {"type":"trace","algorithm":"算法名","items":[...],"activeIndices":[...],"processedRange":[start,end],"action":"compare|swap|insert|delete|visit|mark","explanation":"说明"}。
                - 复杂度分析必须优先使用 {"type":"complexity","time":"O(...)","space":"O(...)","cases":[...],"reason":"原因"}。
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
            case "text" -> "explain";
            case "hook", "recall", "explain", "analogy", "board_write", "diagram", "worked_example",
                    "teacher_question", "student_try", "diagnosis", "mistake_warning", "summary",
                    "transition", "quiz", "formula", "self_check" -> value.toLowerCase();
            default -> "explain";
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
