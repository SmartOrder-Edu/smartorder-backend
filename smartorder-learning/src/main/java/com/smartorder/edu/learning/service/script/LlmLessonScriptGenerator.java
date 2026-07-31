package com.smartorder.edu.learning.service.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartorder.edu.learning.domain.vo.StudyMasteryVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class LlmLessonScriptGenerator {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern INLINE_OPTION_PATTERN = Pattern.compile(
            "(?s)(^|\\s)([A-Ha-h])\\s*[\\.、．)]\\s*(.+?)(?=(\\s+[A-Ha-h]\\s*[\\.、．)]\\s*)|$)");
    private static final double DEFAULT_QUALITY_REVIEW_THRESHOLD = 0.78;

    private final ChatModel chatModel;
    private final LessonTimelineBuilder timelineBuilder;

    @Value("${lesson.script.quality-review.enabled:true}")
    private boolean qualityReviewEnabled;

    @Value("${lesson.script.quality-review.threshold:0.78}")
    private double qualityReviewThreshold;

    public LlmLessonScriptGenerator(@Qualifier("lessonScriptChatModel") ChatModel chatModel,
                                    LessonTimelineBuilder timelineBuilder) {
        this.chatModel = chatModel;
        this.timelineBuilder = timelineBuilder;
    }

    public StudySessionVO generate(LessonScriptContext context) {
        JsonNode root;
        try {
            root = generateByPipeline(context);
        } catch (Exception pipelineError) {
            log.warn("[LessonScript] staged LLM generation failed, fallback to monolithic prompt: taskId={}, topic={}, reason={}",
                    context.getTaskId(), topic(context), pipelineError.getMessage());
            root = generateByMonolithicPrompt(context);
        }
        return buildSession(context, root);
    }

    private JsonNode generateByPipeline(LessonScriptContext context) {
        JsonNode blueprint = parseAndValidateBlueprintOrRepair(context, chatModel.chat(buildBlueprintPrompt(context)));
        JsonNode blueprintSteps = blueprint.path("steps");
        if (!blueprintSteps.isArray() || blueprintSteps.isEmpty()) {
            throw new IllegalStateException("LLM lesson blueprint steps is empty");
        }

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode steps = root.putArray("steps");
        int stepCount = Math.min(blueprintSteps.size(), 6);
        for (int i = 0; i < stepCount; i++) {
            JsonNode planStep = blueprintSteps.path(i);
            JsonNode step = parseAndValidateStepOrRepair(context, blueprint, planStep, i + 1,
                    chatModel.chat(buildStepPrompt(context, blueprint, planStep, i + 1, stepCount)));
            steps.add(reviewAndRepairStepQuality(context, blueprint, planStep, i + 1, step));
        }
        root.set("mastery", blueprint.has("mastery") ? blueprint.path("mastery") : defaultMasteryNode());

        List<String> errors = validateRoot(root);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("LLM staged lesson script contract invalid: " + String.join("; ", errors));
        }
        return root;
    }

    private JsonNode generateByMonolithicPrompt(LessonScriptContext context) {
        String raw = chatModel.chat(buildPrompt(context));
        return parseAndValidateOrRepair(context, raw);
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

    private JsonNode parseAndValidateBlueprintOrRepair(LessonScriptContext context, String raw) {
        List<String> errors;
        try {
            JsonNode blueprint = parseJson(raw);
            errors = validateBlueprint(blueprint);
            if (errors.isEmpty()) {
                return blueprint;
            }
        } catch (Exception e) {
            errors = List.of("LLM blueprint JSON parse failed: " + e.getMessage());
        }

        String repaired = chatModel.chat(buildBlueprintRepairPrompt(context, raw, errors));
        JsonNode repairedBlueprint = parseJson(repaired);
        List<String> repairedErrors = validateBlueprint(repairedBlueprint);
        if (!repairedErrors.isEmpty()) {
            throw new IllegalStateException("LLM lesson blueprint invalid after repair: "
                    + String.join("; ", repairedErrors));
        }
        return repairedBlueprint;
    }

    private JsonNode parseAndValidateStepOrRepair(LessonScriptContext context,
                                                  JsonNode blueprint,
                                                  JsonNode planStep,
                                                  int stepIndex,
                                                  String raw) {
        List<String> errors;
        try {
            JsonNode step = unwrapStepNode(parseJson(raw));
            errors = validateStepNode(step, stepIndex);
            if (errors.isEmpty()) {
                return step;
            }
        } catch (Exception e) {
            errors = List.of("LLM step JSON parse failed: " + e.getMessage());
        }

        String repaired = chatModel.chat(buildStepRepairPrompt(context, blueprint, planStep, stepIndex, raw, errors));
        JsonNode repairedStep = unwrapStepNode(parseJson(repaired));
        List<String> repairedErrors = validateStepNode(repairedStep, stepIndex);
        if (!repairedErrors.isEmpty()) {
            throw new IllegalStateException("LLM lesson step " + stepIndex + " invalid after repair: "
                    + String.join("; ", repairedErrors));
        }
        return repairedStep;
    }

    private JsonNode reviewAndRepairStepQuality(LessonScriptContext context,
                                                JsonNode blueprint,
                                                JsonNode planStep,
                                                int stepIndex,
                                                JsonNode step) {
        if (!qualityReviewEnabled) {
            return step;
        }
        try {
            JsonNode review = parseJson(chatModel.chat(buildStepQualityReviewPrompt(context, blueprint, planStep, stepIndex, step)));
            double score = review.path("overallScore").isNumber() ? review.path("overallScore").asDouble() : 1.0;
            boolean needsRepair = review.path("needsRepair").asBoolean(false);
            if (!needsRepair && score >= normalizedQualityReviewThreshold()) {
                return step;
            }

            String repaired = chatModel.chat(buildStepQualityRepairPrompt(context, blueprint, planStep, stepIndex, step, review));
            JsonNode repairedStep = unwrapStepNode(parseJson(repaired));
            List<String> errors = validateStepNode(repairedStep, stepIndex);
            if (errors.isEmpty()) {
                return repairedStep;
            }
            log.warn("[LessonScript] quality repair discarded because contract validation failed: taskId={}, stepIndex={}, errors={}",
                    context.getTaskId(), stepIndex, String.join("; ", errors));
        } catch (Exception e) {
            log.warn("[LessonScript] step quality review skipped: taskId={}, stepIndex={}, reason={}",
                    context.getTaskId(), stepIndex, e.getMessage());
        }
        return step;
    }

    private double normalizedQualityReviewThreshold() {
        if (qualityReviewThreshold <= 0 || qualityReviewThreshold > 1) {
            return DEFAULT_QUALITY_REVIEW_THRESHOLD;
        }
        return qualityReviewThreshold;
    }

    private JsonNode unwrapStepNode(JsonNode node) {
        if (node != null && node.has("step") && node.path("step").isObject()) {
            return node.path("step");
        }
        return node;
    }

    private List<String> validateBlueprint(JsonNode blueprint) {
        List<String> errors = new ArrayList<>();
        JsonNode steps = blueprint == null ? null : blueprint.path("steps");
        if (steps == null || !steps.isArray()) {
            errors.add("blueprint.steps must be an array");
            return errors;
        }
        if (!isAllowedKnowledgeType(text(blueprint, "knowledgeType", ""))) {
            errors.add("blueprint.knowledgeType is invalid or blank");
        }
        if (text(blueprint, "teachingStrategy", "").isBlank()) {
            errors.add("blueprint.teachingStrategy is blank");
        }
        if (text(blueprint, "strategyRationale", "").isBlank()) {
            errors.add("blueprint.strategyRationale is blank");
        }
        if (steps.size() < 4 || steps.size() > 6) {
            errors.add("blueprint.steps size must be 4 to 6, actual=" + steps.size());
        }

        boolean hasConcept = false;
        boolean hasExampleOrPractice = false;
        boolean hasAssessment = false;
        boolean hasSummary = false;
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.path(i);
            String phase = text(step, "phase", "");
            String canvasType = text(step, "canvasType", "");
            if (text(step, "title", "").isBlank()) {
                errors.add("blueprint step " + (i + 1) + " title is blank");
            }
            if (text(step, "goal", "").isBlank()) {
                errors.add("blueprint step " + (i + 1) + " goal is blank");
            }
            if (text(step, "designRationale", "").isBlank()) {
                errors.add("blueprint step " + (i + 1) + " designRationale is blank");
            }
            if (!step.path("segmentPlan").isArray() || step.path("segmentPlan").isEmpty()) {
                errors.add("blueprint step " + (i + 1) + " segmentPlan must be a non-empty array");
            }
            hasConcept = hasConcept || "concept".equals(canvasType) || "derivation".equals(canvasType)
                    || "concept".equals(phase);
            hasExampleOrPractice = hasExampleOrPractice || "example".equals(canvasType) || "practice".equals(canvasType)
                    || "example".equals(phase) || "practice".equals(phase);
            hasAssessment = hasAssessment || "assessment".equals(canvasType) || "assessment".equals(phase);
            hasSummary = hasSummary || "summary".equals(canvasType) || "summary".equals(phase);
        }
        if (!hasConcept) {
            errors.add("blueprint must include a concept or derivation step");
        }
        if (!hasExampleOrPractice) {
            errors.add("blueprint must include an example or practice step");
        }
        if (!hasAssessment) {
            errors.add("blueprint must include an assessment step");
        }
        if (!hasSummary) {
            errors.add("blueprint must include a summary step");
        }
        return errors;
    }

    private boolean isAllowedKnowledgeType(String value) {
        return switch (safe(value, "").toLowerCase()) {
            case "concept", "procedure", "algorithm", "formula", "comparison", "problem_solving", "misconception" -> true;
            default -> false;
        };
    }

    private List<String> validateStepNode(JsonNode step, int stepIndex) {
        List<String> errors = new ArrayList<>();
        if (step == null || !step.isObject()) {
            errors.add("step " + stepIndex + " must be an object");
            return errors;
        }
        String title = text(step, "title", "");
        String markdown = text(step, "markdown", "");
        String teacherTalk = collectSegmentField(step.path("teachingSegments"), "teacherTalk");
        String boardContent = collectSegmentField(step.path("teachingSegments"), "boardContent");
        String boardBlocks = collectSegmentBoardBlocks(step.path("teachingSegments"));
        String instructionalText = markdown + teacherTalk + boardContent + boardBlocks;
        if (title.isBlank()) {
            errors.add("step " + stepIndex + " title is blank");
        }
        if (!isAllowedCanvasType(text(step, "canvasType", ""))) {
            errors.add("step " + stepIndex + " canvasType is invalid");
        }
        if (instructionalText.length() < 80) {
            errors.add("step " + stepIndex + " instructional content is too short");
        }
        errors.addAll(validateTeachingSegments(step.path("teachingSegments"), stepIndex));
        if (step.has("quiz")) {
            errors.addAll(validateQuizNode(step.path("quiz"), "step " + stepIndex + " quiz"));
        }
        return errors;
    }

    private List<String> validateQuizNode(JsonNode quiz, String path) {
        List<String> errors = new ArrayList<>();
        if (quiz == null || !quiz.isObject()) {
            errors.add(path + " must be an object");
            return errors;
        }
        String answerType = normalizeAnswerType(text(quiz, "answerType", "text"), quiz);
        String correctAnswer = text(quiz, "correctAnswer", text(quiz, "answer", ""));
        if (text(quiz, "prompt", "").isBlank()) {
            errors.add(path + " prompt is blank");
        }
        if (correctAnswer.isBlank()) {
            errors.add(path + " correctAnswer is blank");
        }
        if ("single_select".equals(answerType) || "multi_select".equals(answerType)) {
            JsonNode options = quiz.path("options");
            if (!options.isArray() || options.size() < 2) {
                errors.add(path + " options must contain at least 2 items");
            } else if ("single_select".equals(answerType) && !optionValues(options).contains(correctAnswer)) {
                errors.add(path + " correctAnswer must match one option value");
            }
        }
        return errors;
    }

    private List<String> optionValues(JsonNode options) {
        List<String> values = new ArrayList<>();
        if (!options.isArray()) {
            return values;
        }
        for (JsonNode option : options) {
            String value = text(option, "value", "");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
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
                    "concept_card", "concept-card", "definition_card", "definition-card",
                    "principle_card", "principle-card", "key_takeaway", "key-takeaway",
                    "numbered_points", "numbered-points", "reasoning_steps", "reasoning-steps",
                    "concept_breakdown", "concept-breakdown", "ordered_checklist", "ordered-checklist",
                    "structure", "trace", "operation_sequence", "operation-sequence", "invariant",
                    "complexity", "comparison", "vs_compare", "vs-compare" -> true;
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
            case "concept_card", "concept-card", "definition_card", "definition-card",
                    "principle_card", "principle-card", "key_takeaway", "key-takeaway" ->
                    !text(block, "term", "").isBlank()
                            || !text(block, "definition", "").isBlank()
                            || !text(block, "intuition", "").isBlank()
                            || !text(block, "formal", "").isBlank()
                            || nonEmptyArray(block.path("keyPoints"))
                            || nonEmptyArray(block.path("examples"))
                            || !text(block, "takeaway", "").isBlank();
            case "numbered_points", "numbered-points", "reasoning_steps", "reasoning-steps",
                    "concept_breakdown", "concept-breakdown", "ordered_checklist", "ordered-checklist" ->
                    !text(block, "lead", "").isBlank()
                            || !text(block, "question", "").isBlank()
                            || !text(block, "summary", "").isBlank()
                            || !text(block, "conclusion", "").isBlank()
                            || !text(block, "rule", "").isBlank()
                            || !text(block, "reminder", "").isBlank()
                            || nonEmptyArray(block.path("items"))
                            || nonEmptyArray(block.path("points"))
                            || nonEmptyArray(block.path("steps"));
            case "structure" -> !text(block, "structureType", "").isBlank()
                    || nonEmptyArray(block.path("items"))
                    || nonEmptyArray(block.path("nodes"));
            case "trace" -> nonEmptyArray(block.path("items"))
                    || !text(block, "traceKind", "").isBlank()
                    || !text(block, "action", "").isBlank()
                    || !text(block, "explanation", "").isBlank();
            case "operation_sequence", "operation-sequence" -> nonEmptyArray(block.path("steps"))
                    || !text(block, "operation", "").isBlank()
                    || !text(block, "invariant", "").isBlank();
            case "invariant" -> !text(block, "statement", "").isBlank()
                    || !text(block, "whyItMatters", "").isBlank()
                    || nonEmptyArray(block.path("checkpoints"))
                    || !text(block, "commonMistake", "").isBlank();
            case "complexity" -> !text(block, "time", "").isBlank()
                    || !text(block, "space", "").isBlank()
                    || nonEmptyArray(block.path("cases"))
                    || !text(block, "reason", "").isBlank();
            case "comparison", "vs_compare", "vs-compare" -> block.has("left")
                    || block.has("right")
                    || nonEmptyArray(block.path("criteria"))
                    || !text(block, "summary", "").isBlank();
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
                builder.append(text(block, "summary", ""));
                builder.append(text(block, "structureType", ""));
                builder.append(text(block, "action", ""));
                builder.append(text(block, "explanation", ""));
                builder.append(text(block, "time", ""));
                builder.append(text(block, "space", ""));
                builder.append(text(block, "reason", ""));
                if (block.has("left")) {
                    builder.append(block.path("left"));
                }
                if (block.has("right")) {
                    builder.append(block.path("right"));
                }
                if (block.has("criteria")) {
                    builder.append(block.path("criteria"));
                }
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
        copyBoardBlockField(blockNode, block, "term");
        copyBoardBlockField(blockNode, block, "definition");
        copyBoardBlockField(blockNode, block, "intuition");
        copyBoardBlockField(blockNode, block, "formal");
        copyBoardBlockField(blockNode, block, "keyPoints");
        copyBoardBlockField(blockNode, block, "boundaries");
        copyBoardBlockField(blockNode, block, "conditions");
        copyBoardBlockField(blockNode, block, "examples");
        copyBoardBlockField(blockNode, block, "nonExamples");
        copyBoardBlockField(blockNode, block, "takeaway");
        copyBoardBlockField(blockNode, block, "lead");
        copyBoardBlockField(blockNode, block, "question");
        copyBoardBlockField(blockNode, block, "points");
        copyBoardBlockField(blockNode, block, "conclusion");
        copyBoardBlockField(blockNode, block, "rule");
        copyBoardBlockField(blockNode, block, "context");
        copyBoardBlockField(blockNode, block, "reminder");
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
        copyBoardBlockField(blockNode, block, "visitedIds");
        copyBoardBlockField(blockNode, block, "frontierIds");
        copyBoardBlockField(blockNode, block, "pathIds");
        copyBoardBlockField(blockNode, block, "compareIds");
        copyBoardBlockField(blockNode, block, "indexBase");
        copyBoardBlockField(blockNode, block, "heapType");
        copyBoardBlockField(blockNode, block, "heapSize");
        copyBoardBlockField(blockNode, block, "parentIndex");
        copyBoardBlockField(blockNode, block, "childIndices");
        copyBoardBlockField(blockNode, block, "swapIndices");
        copyBoardBlockField(blockNode, block, "violationIndices");
        copyBoardBlockField(blockNode, block, "sortedRange");
        copyBoardBlockField(blockNode, block, "rootId");
        copyBoardBlockField(blockNode, block, "directed");
        copyBoardBlockField(blockNode, block, "hashFormula");
        copyBoardBlockField(blockNode, block, "formula");
        copyBoardBlockField(blockNode, block, "columns");
        copyBoardBlockField(blockNode, block, "colCount");
        copyBoardBlockField(blockNode, block, "displayLayout");
        copyBoardBlockField(blockNode, block, "layout");
        copyBoardBlockField(blockNode, block, "animation");
        copyBoardBlockField(blockNode, block, "traceKind");
        copyBoardBlockField(blockNode, block, "algorithm");
        copyBoardBlockField(blockNode, block, "processedRange");
        copyBoardBlockField(blockNode, block, "action");
        copyBoardBlockField(blockNode, block, "explanation");
        copyBoardBlockField(blockNode, block, "operation");
        copyBoardBlockField(blockNode, block, "steps");
        copyBoardBlockField(blockNode, block, "currentStep");
        copyBoardBlockField(blockNode, block, "invariant");
        copyBoardBlockField(blockNode, block, "statement");
        copyBoardBlockField(blockNode, block, "whyItMatters");
        copyBoardBlockField(blockNode, block, "checkpoints");
        copyBoardBlockField(blockNode, block, "commonMistake");
        copyBoardBlockField(blockNode, block, "time");
        copyBoardBlockField(blockNode, block, "space");
        copyBoardBlockField(blockNode, block, "caseHeaders");
        copyBoardBlockField(blockNode, block, "cases");
        copyBoardBlockField(blockNode, block, "reason");
        copyBoardBlockField(blockNode, block, "left");
        copyBoardBlockField(blockNode, block, "right");
        copyBoardBlockField(blockNode, block, "criteria");
        copyBoardBlockField(blockNode, block, "summary");
        copyBoardBlockField(blockNode, block, "emphasis");
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
            case "concept_card", "definition_card", "principle_card", "key_takeaway" -> title + "\n\n"
                    + "- 概念：" + block.getOrDefault("term", "") + "\n"
                    + "- 定义：" + block.getOrDefault("definition", "") + "\n"
                    + "- 直觉理解：" + block.getOrDefault("intuition", "") + "\n"
                    + "- 形式化表达：" + block.getOrDefault("formal", "") + "\n"
                    + "- 关键点：" + block.getOrDefault("keyPoints", List.of()) + "\n"
                    + "- 结论：" + block.getOrDefault("takeaway", "");
            case "numbered_points" -> title + "\n\n"
                    + "- 导语：" + block.getOrDefault("lead", "") + "\n"
                    + "- 要点：" + block.getOrDefault("items", List.of()) + "\n"
                    + "- 结论：" + block.getOrDefault("conclusion", block.getOrDefault("summary", ""));
            case "reasoning_steps" -> title + "\n\n"
                    + "- 问题：" + block.getOrDefault("question", "") + "\n"
                    + "- 推理步骤：" + block.getOrDefault("steps", List.of()) + "\n"
                    + "- 结论：" + block.getOrDefault("conclusion", "");
            case "concept_breakdown" -> title + "\n\n"
                    + "- 概念：" + block.getOrDefault("term", "") + "\n"
                    + "- 总述：" + block.getOrDefault("summary", "") + "\n"
                    + "- 拆解：" + block.getOrDefault("points", block.getOrDefault("items", List.of())) + "\n"
                    + "- 规则：" + block.getOrDefault("rule", block.getOrDefault("takeaway", ""));
            case "ordered_checklist" -> title + "\n\n"
                    + "- 使用场景：" + block.getOrDefault("context", "") + "\n"
                    + "- 检查项：" + block.getOrDefault("items", List.of()) + "\n"
                    + "- 提醒：" + block.getOrDefault("reminder", "");
            case "structure" -> title + "\n\n"
                    + "- 类型：" + block.getOrDefault("structureType", "数据结构") + "\n"
                    + "- 元素：" + block.getOrDefault("items", block.getOrDefault("nodes", List.of())) + "\n"
                    + "- 指针：" + block.getOrDefault("pointers", Map.of());
            case "trace" -> title + "\n\n"
                    + "- 过程类型：" + block.getOrDefault("traceKind", "") + "\n"
                    + "- 动作：" + block.getOrDefault("action", "标记") + "\n"
                    + "- 当前序列：" + block.getOrDefault("items", List.of()) + "\n"
                    + "- 指针：" + block.getOrDefault("pointers", Map.of()) + "\n"
                    + "- 说明：" + block.getOrDefault("explanation", "");
            case "operation_sequence" -> title + "\n\n"
                    + "- 操作：" + block.getOrDefault("operation", "") + "\n"
                    + "- 步骤：" + block.getOrDefault("steps", List.of()) + "\n"
                    + "- 保持规则：" + block.getOrDefault("invariant", "");
            case "invariant" -> title + "\n\n"
                    + "- 不变量：" + block.getOrDefault("statement", "") + "\n"
                    + "- 为什么重要：" + block.getOrDefault("whyItMatters", "") + "\n"
                    + "- 检查点：" + block.getOrDefault("checkpoints", List.of()) + "\n"
                    + "- 常见破坏方式：" + block.getOrDefault("commonMistake", "");
            case "complexity" -> title + "\n\n"
                    + "- 时间复杂度：" + block.getOrDefault("time", "待分析") + "\n"
                    + "- 空间复杂度：" + block.getOrDefault("space", "待分析") + "\n"
                    + "- 原因：" + block.getOrDefault("reason", "");
            case "comparison", "vs_compare" -> title + "\n\n"
                    + "- 左侧：" + block.getOrDefault("left", Map.of()) + "\n"
                    + "- 右侧：" + block.getOrDefault("right", Map.of()) + "\n"
                    + "- 对比维度：" + block.getOrDefault("criteria", List.of()) + "\n"
                    + "- 结论：" + block.getOrDefault("summary", "");
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
            case "code", "formula", "list", "quote", "diagram", "callout", "concept_card",
                    "definition_card", "principle_card", "key_takeaway",
                    "numbered_points", "reasoning_steps", "concept_breakdown", "ordered_checklist",
                    "structure", "trace", "operation_sequence", "invariant",
                    "complexity", "comparison", "vs_compare" -> type.toLowerCase();
            case "concept-card" -> "concept_card";
            case "definition-card" -> "definition_card";
            case "principle-card" -> "principle_card";
            case "key-takeaway" -> "key_takeaway";
            case "operation-sequence" -> "operation_sequence";
            case "vs-compare" -> "vs_compare";
            case "numbered-points" -> "numbered_points";
            case "reasoning-steps" -> "reasoning_steps";
            case "concept-breakdown" -> "concept_breakdown";
            case "ordered-checklist" -> "ordered_checklist";
            default -> "markdown";
        };
    }

    private String defaultBoardBlockTitle(String type) {
        return switch (type) {
            case "code" -> "示例代码";
            case "formula" -> "关键公式";
            case "diagram" -> "结构图";
            case "callout" -> "提示";
            case "concept_card" -> "概念讲解";
            case "definition_card" -> "定义说明";
            case "principle_card" -> "规则说明";
            case "key_takeaway" -> "关键结论";
            case "numbered_points" -> "编号要点";
            case "reasoning_steps" -> "推理步骤";
            case "concept_breakdown" -> "概念拆解";
            case "ordered_checklist" -> "检查清单";
            case "structure" -> "数据结构状态";
            case "trace" -> "算法过程";
            case "operation_sequence" -> "操作步骤";
            case "invariant" -> "关键不变量";
            case "complexity" -> "复杂度分析";
            case "comparison", "vs_compare" -> "对比分析";
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

    private String buildBlueprintPrompt(LessonScriptContext context) {
        return """
                你是一个严谨的中文课程教学设计专家。请先为当前知识点设计“学习教程脚本蓝图”，不要生成最终板书内容，不要生成完整 teachingSegments。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                输出结构：
                {
                  "knowledgeType": "concept|procedure|algorithm|formula|comparison|problem_solving|misconception",
                  "teachingStrategy": "本节课采用的整体教学策略",
                  "strategyRationale": "为什么这种教学策略适合当前知识点和学生状态",
                  "coreMisconceptions": ["学生最可能出现的误区"],
                  "steps": [
                    {
                      "phase": "review|concept|example|practice|assessment|summary",
                      "nodeType": "concept_review|worked_example|guided_practice|diagnostic_quiz|summary",
                      "title": "步骤标题",
                      "subtitle": "一句话说明",
                      "goal": "本步骤明确教学目标",
                      "designRationale": "为什么这一步放在这里，它解决学生的哪个认知障碍",
                      "estimatedMinutes": 3-20,
                      "canvasType": "concept|example|practice|assessment|derivation|summary",
                      "keyPoints": ["必须讲清楚的关键点"],
                      "commonMistakes": ["本步骤要预防或诊断的错误"],
                      "expectedBoardTypes": ["markdown|formula|callout|numbered_points|reasoning_steps|concept_breakdown|ordered_checklist|structure|trace|complexity|vs_compare|code|diagram"],
                      "interactionNeed": "none|teacher_question|student_try|quiz|self_check",
                      "segmentPlan": [
                        {"type": "hook|recall|explain|board_write|worked_example|teacher_question|student_try|diagnosis|mistake_warning|summary|transition|quiz|formula", "intent": "这一段为什么这样讲", "boardHint": "建议使用的板书类型"}
                      ]
                    }
                  ],
                  "mastery": {"level": "unknown|weak|progressing|mastered", "levelLabel": "中文标签", "hint": "学习判断", "nextSuggestion": "下一步建议"}
                }

                教学设计必须遵循以下方法论：
                1. 目标倒推：每个 step 必须服务于最终学习目标，不允许只罗列知识点。
                2. 认知负荷控制：每一步只引入 1-2 个新概念；复杂内容拆成“直觉解释 → 形式化表达 → 例子验证”。
                3. 前置诊断：依赖前置概念时，必须安排 recall 或 teacher_question 检查学生是否具备前置理解。
                4. 具体到抽象：新概念优先从具体例子、反例或可观察现象进入，再总结定义、公式或规则。
                5. 错误驱动：每节课必须设计至少一个常见错误，并解释“为什么会错”和“如何避免”。
                6. 主动提取：总结必须形成一句可复用判断规则，而不是复述知识点。
                7. 迁移意识：练习或诊断要改变一个关键条件，检查学生是否能迁移。
                8. 结构化表达：当概念、规则、步骤或检查项超过 3 个时，必须规划编号化板书，避免长段落堆叠。

                知识类型对应策略：
                - concept：必须包含定义、反例、边界条件、易混概念对比。
                - procedure：必须包含步骤拆解、每步目的、常见漏步。
                - algorithm：必须包含状态变化、执行跟踪、复杂度分析、边界情况。
                - formula：必须包含公式含义、适用条件、推导直觉、错误套用场景。
                - comparison：必须使用 vs_compare 比较适用条件、核心差异、选择规则。
                - problem_solving：必须包含审题、方法选择、关键判断、变式题。
                - misconception：必须先暴露错误直觉，再用反例或诊断纠正。

                教学动作库：
                - hook：制造问题感，只提出一个学生能感知的问题。
                - recall：检查前置知识，不讲新内容。
                - explain：解释核心概念，只解决一个认知点。
                - board_write：把口头解释压缩成可视化板书。
                - worked_example：老师带做，显式说明每一步为什么做。
                - student_try：让学生预测下一步或做一个关键判断。
                - diagnosis：根据可能错误解释错因。
                - mistake_warning：提前指出高频误区。
                - summary：沉淀为可复用规则。

                规则：
                1. steps 数量 4 到 6 个，必须形成清晰递进：导入/回忆 → 概念 → 例题或练习 → 诊断 → 总结。
                2. 必须至少包含一个 concept 或 derivation 步骤、一个 example 或 practice 步骤、一个 assessment 步骤、一个 summary 步骤。
                3. 至少一个步骤要覆盖常见误区，至少三个步骤要安排互动或停顿。
                4. 只做教学设计，不要生成 boardBlocks 的具体字段内容。
                5. 内容必须围绕上下文知识点，不要使用无关默认示例。
                6. 每个 step 的 designRationale 必须说明该 step 解决的具体认知障碍。

                学习上下文：
                %s
                """.formatted(contextSummary(context));
    }

    private String buildBlueprintRepairPrompt(LessonScriptContext context, String invalidJson, List<String> errors) {
        return """
                你是学习教程蓝图 JSON 修复器。只修复蓝图结构，不生成完整教学脚本。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                必须满足：
                - 必须有 knowledgeType，且只能是 concept、procedure、algorithm、formula、comparison、problem_solving、misconception。
                - 必须有 teachingStrategy 和 strategyRationale。
                - steps 数量 4 到 6。
                - 每个 step 必须有 phase、nodeType、title、subtitle、goal、designRationale、estimatedMinutes、canvasType、keyPoints、segmentPlan。
                - canvasType 只能是 concept、example、practice、assessment、derivation、summary。
                - 必须包含 concept 或 derivation、example 或 practice、assessment、summary。
                - 每个 segmentPlan 至少 2 项，type 必须是课堂教学段类型。
                - 每个 step 的 designRationale 必须说明该 step 解决的具体认知障碍。
                - 内容必须围绕知识点，不要使用无关默认示例。

                学习上下文：
                %s

                错误列表：
                %s

                待修复 JSON：
                %s
                """.formatted(
                contextSummary(context),
                "- " + String.join("\n- ", errors),
                invalidJson == null ? "" : invalidJson
        );
    }

    private String buildStepPrompt(LessonScriptContext context, JsonNode blueprint, JsonNode planStep,
                                   int stepIndex, int totalSteps) {
        return """
                你是一个严谨的中文学习教程脚本生成器。请根据“整课蓝图”和“当前 step 计划”，只生成当前一个 step 的完整教学脚本。

                只输出一个 JSON 对象，不要包在 {"steps":[...]} 中，不要 Markdown 代码块，不要解释，不要额外文本。

                当前 step 序号：%s/%s

                当前 step 计划：
                %s

                整课蓝图摘要：
                %s

                当前 step 输出结构：
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
                      "type": "hook|recall|explain|analogy|board_write|diagram|worked_example|teacher_question|student_try|diagnosis|mistake_warning|summary|transition|quiz|formula|self_check",
                      "teacherTalk": "老师真实说出口的话，像面对学生讲课，不要写成资料摘要",
                      "boardContent": "兼容字段：黑板/画布上逐步出现的 Markdown 内容",
                      "boardBlocks": [%s],
                      "markdown": "兼容字段，通常等于 boardContent 或本段展示内容",
                      "teachingIntent": "说明本段为什么这样讲",
                      "emotionTone": "curious|encourage|serious|warning|summary",
                      "interaction": {"type": "pause|single_select|multi_select|text_answer|predict|self_check", "prompt": "课堂提问", "options": [{"label": "选项文本", "value": "a"}], "correctAnswer": "a", "feedbackWhenCorrect": "答对反馈", "feedbackWhenWrong": "答错反馈"},
                      "pacing": {"autoAdvanceMs": 3600, "waitForUser": false}
                    }
                  ],
                  "quiz": {"prompt": "题干，不要把 A/B/C 选项写在这里", "answerType": "text|single_select|multi_select|number", "options": [{"label": "展示给学生的选项文本", "value": "a"}], "answer": "参考答案或选项 value", "correctAnswer": "参考答案或选项 value", "hint": "提示", "explanation": "解析"},
                  "exampleSteps": [{"id": "ex1", "label": "步骤名", "markdown": "例题步骤"}]
                }

                规则：
                1. 只生成当前 step，不要改写其他 step。
                2. 必须严格遵循当前 step 计划的 goal、keyPoints、segmentPlan。
                3. teachingSegments 至少 2 段；teacherTalk 要自然口语，boardBlocks/boardContent 要精炼。
                4. 当前 step 的 teacherTalk + boardContent + boardBlocks 内容总量至少 160 个中文字符。
                5. 如果当前 step 是 assessment、student_try、teacher_question、quiz 或 self_check，必须提供可作答的 quiz 或 interaction。
                6. 选择题 options 必须是 {"label":"展示给学生的选项文本","value":"a|b|c|d"}，correctAnswer 必须填写 option.value。
                7. boardBlocks 优先使用当前 step 计划里的 expectedBoardTypes，不要只用普通 markdown。
                8. 对比两个概念、方法、公式、解法、易混点时优先使用 vs_compare。
                9. 复杂度分析必须使用 complexity；caseHeaders 可自定义表头，不需要时可省略。
                10. 数据结构/算法讲解优先使用 structure、trace、complexity。
                11. 内容必须围绕上下文知识点，不要使用无关默认示例。
                12. boardBlocks[*].content 必须是纯内容：代码不要包 ```，Mermaid 图不要包 ```mermaid。
                13. 概念、规则、步骤、检查清单能拆成 3 个以上同维度条目时，优先使用 numbered_points、reasoning_steps、concept_breakdown 或 ordered_checklist，不要只写长段 markdown。
                14. boardBlocks[*].displayLayout 是可选桌面端版面提示，格式为 {"groupId":"同组标识","role":"visual|explanation|concept|code|note|summary|support","span":"full|wide|side|half|third"}；不要把板书版面对象写入 layout，layout 保留给结构图内部布局等旧语义。
                15. 图示/结构/trace/code 与讲解文字属于同一认知点时，必须使用相同 displayLayout.groupId：视觉块用 role=visual/code 且 span=wide，解释、步骤或提示块用 role=explanation/note 且 span=side。
                16. 概念卡、定义卡、关键结论较短时优先 span=third 或 half；长代码、长对比、复杂图示优先 span=full。

                教学内容质量要求：
                17. teacherTalk、boardBlocks、interaction 必须分工明确：teacherTalk 负责引导和解释，boardBlocks 负责可视化和结构化呈现，interaction 负责检查理解，三者不能互相替代。
                18. 每个 teachingSegment 只解决一个主要认知点；如果需要两个以上概念，必须拆成多个 segment。
                19. 讲新概念时必须先建立直觉，再给形式化表达，最后用例子或反例确认。
                20. worked_example 必须解释每一步“为什么这样做”，不能只列计算过程。
                21. practice 或 student_try 必须相对例题改变一个关键条件，用于检查迁移能力。
                22. diagnosis 或 mistake_warning 必须指出具体错因，不要只说“注意条件”。
                23. summary 必须沉淀为一条可复用判断规则，例如“看到 X，先检查 Y，再选择 Z”。
                24. quiz 的干扰项必须对应真实常见误区，解析要解释为什么错误选项不对。

                boardBlocks 选择原则：
                - 概念定义：definition_card，必须填写 term、definition，最好补充 intuition 或 formal。
                - 概念理解：concept_card，适合同时呈现定义、直觉、关键点、边界、正反例。
                - 概念拆解：concept_breakdown，适合把一个概念拆成 3-5 个同维度要点，必须填写 term、summary、points。
                - 核心要点：numbered_points，适合呈现 3-6 个并列知识点，每项要短，字段用 items。
                - 推理链路：reasoning_steps，适合解释“为什么”，每步填写 title/step、reason、result。
                - 复盘检查：ordered_checklist，适合总结前检查或解题前检查，每项必须可操作。
                - 规则/原则：principle_card，必须填写 keyPoints、conditions 或 takeaway。
                - 可迁移结论：key_takeaway，用一句判断规则帮助学生复用。
                - 普通补充说明：markdown + callout。
                - 公式含义和推导：formula。
                - 代码或伪代码：code。
                - 算法执行过程：trace，数据结构课程必须优先填写 traceKind、items、pointers、activeIndices、action、explanation。
                - 数据结构状态：structure。
                - 入栈/出栈、入队/出队、链表插入删除、堆调整等操作步骤：operation_sequence。
                - 二分查找、循环、遍历、堆性质等必须始终保持的判断规则：invariant。
                - 复杂度：complexity。
                - 方法区别、易混概念、适用条件对比：vs_compare。
                - 流程判断：diagram。
                不要为了使用结构化组件而使用；只有当它能降低理解负担时才使用。

                输出前请内部自检，但不要输出自检过程：
                - 是否每个 segment 都服务于当前 step 目标？
                - 是否先建立直觉再形式化？
                - 是否有具体例子、反例或状态变化？
                - 是否至少暴露或预防一个真实误区？
                - 互动是否检查关键理解？
                - boardBlocks 是否与 teacherTalk 对应？
                - 总结是否形成可复用规则？

                学习上下文：
                %s
                """.formatted(
                stepIndex,
                totalSteps,
                compactJson(planStep),
                compactBlueprintSummary(blueprint),
                boardBlockExamplesForPrompt(),
                contextSummary(context)
        );
    }

    private String buildStepRepairPrompt(LessonScriptContext context, JsonNode blueprint, JsonNode planStep,
                                         int stepIndex, String invalidJson, List<String> errors) {
        return """
                你是学习教程 step JSON 局部修复器。你只能修复当前一个 step，不要输出整课 steps。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                当前 step 序号：%s

                当前 step 计划：
                %s

                整课蓝图摘要：
                %s

                必须满足：
                - 输出对象必须是单个 step，不要包在 {"steps":[...]} 中。
                - 必须有 title、subtitle、estimatedMinutes、canvasType、markdown、teachingSegments。
                - canvasType 只能是 concept、example、practice、assessment、derivation、summary。
                - teachingSegments 必须是非空数组，每段必须有 teacherTalk、boardBlocks、boardContent 或 markdown 之一。
                - boardBlocks[*].type 只能是 markdown、code、formula、list、quote、diagram、callout、concept_card、definition_card、principle_card、key_takeaway、numbered_points、reasoning_steps、concept_breakdown、ordered_checklist、structure、trace、operation_sequence、invariant、complexity、comparison、vs_compare。
                - code 必须有 language 和纯代码 content。
                - structure 必须有 structureType。
                - complexity 使用 time、space、caseHeaders、cases、reason。
                - vs_compare 使用 left、right、criteria、summary、emphasis。
                - numbered_points 使用 items，每项包含 title/name 和 content/detail。
                - reasoning_steps 使用 question、steps、conclusion，每步包含 title/step、reason、result。
                - concept_breakdown 使用 term、summary、points、rule。
                - ordered_checklist 使用 context、items、reminder。
                - boardBlocks[*].displayLayout 可选，格式只能是 {"groupId":"同组标识","role":"visual|explanation|concept|code|note|summary|support","span":"full|wide|side|half|third"}；图示/结构/trace/code 与配套解释必须使用同一个 groupId；不要把这个对象写入 layout。
                - quiz.answerType 只能是 text、single_select、multi_select、number。
                - 选择题 correctAnswer 必须匹配 options.value。
                - 内容必须围绕知识点，不要使用无关默认示例。

                学习上下文：
                %s

                错误列表：
                %s

                待修复 step JSON：
                %s
                """.formatted(
                stepIndex,
                compactJson(planStep),
                compactBlueprintSummary(blueprint),
                contextSummary(context),
                "- " + String.join("\n- ", errors),
                invalidJson == null ? "" : invalidJson
        );
    }

    private String buildStepQualityReviewPrompt(LessonScriptContext context, JsonNode blueprint, JsonNode planStep,
                                                int stepIndex, JsonNode step) {
        return """
                你是严格的教学质量评审专家。请评审当前学习教程 step 是否真的能帮助学生学懂知识。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                输出结构：
                {
                  "overallScore": 0.0,
                  "needsRepair": true,
                  "scores": {
                    "topicAlignment": 0.0,
                    "teachingProgression": 0.0,
                    "cognitiveLoad": 0.0,
                    "exampleQuality": 0.0,
                    "interactionQuality": 0.0,
                    "misconceptionCoverage": 0.0,
                    "boardBlockFit": 0.0,
                    "languageNaturalness": 0.0,
                    "transferSupport": 0.0
                  },
                  "issues": [
                    {"scope": "teacherTalk|boardBlocks|quiz|summary|segment:1", "severity": "low|medium|high", "reason": "具体问题", "suggestedRepair": "具体修复建议"}
                  ],
                  "strengths": ["已经做得好的地方"]
                }

                评审标准：
                1. 主题贴合：是否紧扣当前知识点，是否有无关默认例子。
                2. 教学递进：是否从直觉、前置、核心概念、例子、练习或诊断逐步推进。
                3. 认知负荷：是否每段只解决 1 个主要认知点，是否避免一次塞太多术语。
                4. 例题质量：是否使用最小可说明例，是否解释“为什么这样做”。
                5. 互动质量：提问是否检查关键理解，而不是寒暄或机械确认。
                6. 误区覆盖：是否指出学生最容易错在哪里，以及为什么错。
                7. 板书适配：boardBlocks 是否真正降低理解负担，是否和 teacherTalk 对应。
                8. 语言自然度：teacherTalk 是否像老师面对学生讲课。
                9. 迁移支持：是否沉淀出可复用判断规则，而不是只讲当前例子。
                10. 结构清晰度：概念属性、推理步骤、复盘清单是否用编号结构呈现，编号项是否同维度且短小。

                如果 overallScore < 0.78，或存在 high severity issue，needsRepair 必须为 true。

                学习上下文：
                %s

                当前 step 序号：%s
                当前 step 计划：
                %s

                整课蓝图摘要：
                %s

                待评审 step：
                %s
                """.formatted(
                contextSummary(context),
                stepIndex,
                compactJson(planStep),
                compactBlueprintSummary(blueprint),
                compactJson(step)
        );
    }

    private String buildStepQualityRepairPrompt(LessonScriptContext context, JsonNode blueprint, JsonNode planStep,
                                                int stepIndex, JsonNode step, JsonNode review) {
        return """
                你是学习教程 step 教学质量修复器。请只修复当前一个 step 的教学质量，不要输出整课 steps。

                只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要额外文本。

                修复目标：
                1. 让学生真的学懂当前知识点，而不是只看到资料摘要。
                2. 保持当前 step 的 phase、nodeType、canvasType 和主要教学目标不变。
                3. 尽量保持已有 boardBlocks 类型不变；除非原类型明显不适合教学目标。
                4. 优先重写 teacherTalk、boardContent、quiz.explanation、summary 类内容。
                5. 修复后仍必须满足前端协议和 JSON 合同。

                必须遵循的教学原则：
                - 先建立直觉，再形式化表达。
                - 每个 segment 只解决 1 个主要认知点。
                - 例题必须解释每一步为什么合理。
                - 互动题必须检查关键误区或关键判断。
                - 总结必须沉淀成可复用的判断规则。
                - 概念属性、推理步骤和复盘检查项超过 3 个时，必须使用编号化 boardBlocks，而不是长段落。
                - 不允许使用与上下文无关的默认例子。

                当前 step 序号：%s
                学习上下文：
                %s

                当前 step 计划：
                %s

                整课蓝图摘要：
                %s

                质量评审结果：
                %s

                待修复 step：
                %s
                """.formatted(
                stepIndex,
                contextSummary(context),
                compactJson(planStep),
                compactBlueprintSummary(blueprint),
                compactJson(review),
                compactJson(step)
        );
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
                            {"type": "markdown|code|formula|list|quote|diagram|callout|concept_card|definition_card|principle_card|key_takeaway|numbered_points|reasoning_steps|concept_breakdown|ordered_checklist|structure|trace|operation_sequence|invariant|complexity|comparison|vs_compare", "title": "板书块标题", "content": "普通文本或代码纯内容", "caption": "简短说明", "tone": "info|warning|success|error", "displayLayout": {"groupId": "同一认知点分组", "role": "visual|explanation|concept|code|note|summary|support", "span": "full|wide|side|half|third"}},
                            {"type": "numbered_points", "title": "核心要点", "lead": "先把这个知识点拆成几个同维度判断", "items": [{"title": "要点一", "content": "短解释", "example": "必要时给最小例子"}], "conclusion": "一句可复用结论"},
                            {"type": "reasoning_steps", "title": "为什么这样做", "question": "为什么这个条件成立后可以这样判断？", "steps": [{"step": "先确认条件", "reason": "没有条件就不能套用规则", "result": "确定方法适用"}], "conclusion": "推理结束后得到的判断规则"},
                            {"type": "concept_breakdown", "title": "概念拆解", "term": "概念名", "summary": "一句话总述", "points": [{"name": "组成部分", "detail": "解释这个部分的作用"}], "rule": "检查这个概念时的判断规则"},
                            {"type": "ordered_checklist", "title": "解题前检查", "context": "什么时候使用这张清单", "items": [{"text": "检查项", "detail": "为什么要检查"}], "reminder": "最后提醒"},
                            {"type": "code", "title": "代码标题", "language": "c|cpp|java|python|javascript|typescript|go|rust|sql", "content": "纯代码，不要包含 Markdown 代码围栏", "highlights": [2, 3], "focusLine": 3},
                            {"type": "structure", "structureType": "array|stack|queue|linked_list|tree|heap|graph", "title": "结构状态标题", "items": [1, 2, 3], "nodes": [{"id": "A", "label": "A", "depth": 0, "order": 0}], "edges": [{"from": "A", "to": "B", "label": "可选边权"}], "pointers": {"front": 0, "rear": 3, "top": 2}, "activeIndices": [0], "activeIds": ["A"], "visitedIds": ["A"], "frontierIds": ["B"], "pathIds": ["A", "B"], "compareIds": ["1", "4"], "indexBase": 0, "heapType": "max|min", "heapSize": 3, "parentIndex": 0, "childIndices": [1, 2], "swapIndices": [0, 2], "violationIndices": [], "sortedRange": [3, 5], "directed": false, "animation": {"action": "enqueue|dequeue|push|pop|compare|swap|sift_up|sift_down|heapify|extract", "durationMs": 1800, "movingItem": 4, "from": "rear", "to": 3, "previous": {"items": [1, 2, 3], "pointers": {"front": 0, "rear": 3}}, "next": {"items": [1, 2, 3, 4], "pointers": {"front": 0, "rear": 4}}, "phases": [{"name": "before", "startRatio": 0, "items": [1, 2, 3], "pointers": {"front": 0, "rear": 3}, "narrationHint": "入队前"}, {"name": "moving", "startRatio": 0.34, "items": [1, 2, 3], "pointers": {"front": 0, "rear": 3}, "movingItem": 4, "targetIndex": 3, "highlightPointers": ["rear"], "narrationHint": "写入 rear 位置"}, {"name": "after", "startRatio": 0.72, "items": [1, 2, 3, 4], "pointers": {"front": 0, "rear": 4}, "narrationHint": "rear 后移"}]}},
                            {"type": "trace", "algorithm": "bubble_sort|binary_search|dfs|bfs|...", "title": "过程标题", "items": [5, 2, 8], "activeIndices": [0, 1], "processedRange": [2, 2], "action": "compare|swap|insert|delete|visit|mark", "explanation": "当前步骤解释"},
                            {"type": "complexity", "title": "复杂度标题", "time": "O(n)", "space": "O(1)", "caseHeaders": {"label": "场景", "value": "复杂度", "description": "说明"}, "cases": [{"label": "最坏情况", "value": "O(n)", "description": "原因"}], "reason": "复杂度原因"},
                            {"type": "vs_compare", "title": "方法 A vs 方法 B", "left": {"title": "方法 A", "subtitle": "适用场景", "badge": "基础方法", "content": "核心步骤或公式", "strengths": ["优势"], "weaknesses": ["注意点"]}, "right": {"title": "方法 B", "subtitle": "适用场景", "badge": "进阶方法", "content": "核心步骤或公式", "strengths": ["优势"], "weaknesses": ["注意点"]}, "criteria": [{"label": "计算步数", "left": "较多", "right": "较少", "winner": "right", "explanation": "原因"}], "summary": "什么时候优先选哪种方法", "emphasis": "left|right|balanced"}
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
                12. 当讲解包含 3 个以上并列要点、推理步骤、概念组成或复盘检查项时，必须优先使用 numbered_points、reasoning_steps、concept_breakdown 或 ordered_checklist，让学生看到清晰的 1、2、3、4 结构。
                13. 如果知识点属于数据结构或算法，必须优先使用结构化板书：结构状态用 type=structure，排序/查找/遍历过程用 type=trace，复杂度结论用 type=complexity。
                14. structure 当前线性结构优先使用 structureType=array|stack|queue|linked_list|hash_table|matrix，items 表示元素，pointers 表示 front/rear/top/head/left/right/current 等指针，activeIndices 表示当前高亮下标；hash_table 可补 hashFormula，matrix 的 items 可以是二维数组，也可用 columns/colCount 指定列数。
                15. structureType=tree 时使用 nodes、edges、activeIds、visitedIds、pathIds；nodes 可带 id、label、depth、order。
                16. structureType=heap 时使用 items、indexBase、activeIndices、compareIds；items 是堆的紧凑数组存储顺序。默认 indexBase=0，items 禁止添加前置 null、"-" 或占位元素；如果教学必须展示 1-based 下标，必须显式填写 indexBase=1，且 activeIndices、compareIds、parentIndex、childIndices、swapIndices、violationIndices、sortedRange 都使用同一基准。堆讲解必须补充 heapType=max|min；比较父子时填写 parentIndex、childIndices；交换时填写 swapIndices；发现违反堆性质时填写 violationIndices；堆排序或 extract 后如数组尾部已经有序，填写 heapSize 和 sortedRange。
                17. structureType=graph 时使用 nodes、edges、activeIds、visitedIds、frontierIds、pathIds、directed；edges 使用 from/to/label。
                18. 讲解数组插入/删除/交换、栈 push/pop、队列 enqueue/dequeue、链表插入/删除、哈希表 search/probe/insert、矩阵访问/update、树插入/删除/遍历、图 BFS/DFS/最短路径、堆 compare/swap/sift_up/sift_down/extract 时，structure 必须优先提供 animation.phases，至少包含 before、moving、after 三个阶段；before 使用操作前 items/nodes/edges/pointers，moving 使用旧状态加 movingItem/targetIndex/sourceIndex/activeIds/activeIndices/frontierIds/swapIndices，after 使用操作后状态，不能一开始就只给最终状态。
                19. trace 当前优先用 items、activeIndices、processedRange、action、explanation 表示算法执行过程。
                20. complexity 使用 time、space、caseHeaders、cases、reason，不要把复杂度结论只写在普通段落里；caseHeaders 可自定义 cases 表头，不需要自定义时可省略。
                21. 对比两个概念、方法、公式、解法、易混点时优先使用 type=vs_compare 或 type=comparison；必须提供 left、right、criteria、summary，criteria.winner 可为 left、right、tie。
                22. boardBlocks[*].content 必须是纯内容：代码不要包 ```，Mermaid 图不要包 ```mermaid；前端会根据 type 和 language 负责渲染。
                23. boardBlocks[*].displayLayout 是可选桌面端版面提示，格式为 {"groupId":"同组标识","role":"visual|explanation|concept|code|note|summary|support","span":"full|wide|side|half|third"}；不要把板书版面对象写入 layout，layout 保留给结构图内部布局等旧语义。
                24. 图示/结构/trace/code 与讲解文字属于同一认知点时，必须使用相同 displayLayout.groupId：视觉块用 role=visual/code 且 span=wide，解释、步骤或提示块用 role=explanation/note 且 span=side。
                25. 概念卡、定义卡、关键结论较短时优先 span=third 或 half；长代码、长对比、复杂图示优先 span=full。

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
                - 每个 teachingSegment 应优先提供 boardBlocks；boardBlocks[*].type 只能是 markdown、code、formula、list、quote、diagram、callout、concept_card、definition_card、principle_card、key_takeaway、numbered_points、reasoning_steps、concept_breakdown、ordered_checklist、structure、trace、operation_sequence、invariant、complexity、comparison、vs_compare。
                - 代码必须放入 {"type":"code","language":"c|java|python|javascript|...","content":"纯代码"}，content 不要包含 Markdown 代码围栏。
                - 编号化讲解必须优先使用 {"type":"numbered_points","items":[{"title":"要点","content":"解释"}],"conclusion":"结论"}、{"type":"reasoning_steps","question":"为什么","steps":[{"step":"步骤","reason":"原因","result":"结果"}],"conclusion":"结论"}、{"type":"concept_breakdown","term":"概念","summary":"总述","points":[{"name":"组成","detail":"解释"}],"rule":"判断规则"} 或 {"type":"ordered_checklist","items":[{"text":"检查项","detail":"说明"}],"reminder":"提醒"}。
                - 数据结构状态必须优先使用 {"type":"structure","structureType":"array|stack|queue|linked_list|tree|heap|graph|hash_table|matrix","items":[...],"nodes":[...],"edges":[...],"pointers":{...},"activeIndices":[...],"activeIds":[...],"visitedIds":[...],"frontierIds":[...],"pathIds":[...],"compareIds":[...],"indexBase":0,"heapType":"max|min","heapSize":6,"parentIndex":0,"childIndices":[1,2],"swapIndices":[0,2],"violationIndices":[],"sortedRange":[3,5],"directed":false,"hashFormula":"hash(key) % m","columns":3,"animation":{...}}。
                - 常见数据结构操作讲解应保留 animation.phases，至少包含 before、moving、after；before 使用操作前 items/nodes/edges/pointers，moving 使用旧状态加 movingItem/targetIndex/sourceIndex/activeIds/activeIndices/frontierIds/swapIndices/parentIndex/childIndices，after 使用操作后状态。phase 允许携带 items、nodes、edges、pointers、activeIndices、activeIds、visitedIds、frontierIds、pathIds、compareIds、parentIndex、childIndices、swapIndices、violationIndices、sortedRange、heapSize、narrationHint。适用动作包括 array insert/delete/compare/swap、stack push/pop/peek、queue enqueue/dequeue、linked_list insert/delete/traverse、hash_table hash/probe/search/insert、matrix visit/update、tree insert/delete/traverse、heap compare/swap/sift_up/sift_down/heapify/extract、graph bfs/dfs/visit/path。
                - 算法推演必须优先使用 {"type":"trace","algorithm":"算法名","items":[...],"activeIndices":[...],"processedRange":[start,end],"action":"compare|swap|insert|delete|visit|mark","explanation":"说明"}。
                - 复杂度分析必须优先使用 {"type":"complexity","time":"O(...)","space":"O(...)","caseHeaders":{"label":"场景","value":"复杂度","description":"说明"},"cases":[...],"reason":"原因"}，caseHeaders 可省略。
                - 对比两个概念、方法、解法或易混点时，必须优先使用 {"type":"vs_compare","left":{...},"right":{...},"criteria":[...],"summary":"结论","emphasis":"left|right|balanced"}。
                - boardBlocks[*].displayLayout 可选，格式只能是 {"groupId":"同组标识","role":"visual|explanation|concept|code|note|summary|support","span":"full|wide|side|half|third"}；图示/结构/trace/code 与配套解释必须使用同一个 groupId；不要把这个对象写入 layout。
                - quiz.answerType 只能是 text、single_select、multi_select、number。
                - 当 quiz.answerType 为 single_select 或 multi_select 时，quiz.options 必须是数组，且每项包含 label 和 value；correctAnswer 必须填写选项 value。
                - answer 和 correctAnswer 都必须有值。
                - 内容必须围绕知识点，不要使用无关默认示例。
                - teacherTalk 必须像老师讲课，不能只是资料摘要。
                - worked_example 必须解释每一步为什么这样做。
                - student_try、teacher_question 或 quiz 必须检查关键理解或常见误区。
                - summary 必须沉淀为可复用判断规则。

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

    private String contextSummary(LessonScriptContext context) {
        return """
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

    private String boardBlockExamplesForPrompt() {
        return """
                {"type": "markdown|code|formula|list|quote|diagram|callout|concept_card|definition_card|principle_card|key_takeaway|numbered_points|reasoning_steps|concept_breakdown|ordered_checklist|structure|trace|operation_sequence|invariant|complexity|comparison|vs_compare", "title": "板书块标题", "content": "普通文本或代码纯内容", "caption": "简短说明", "tone": "info|warning|success|error", "displayLayout": {"groupId": "同一认知点分组", "role": "visual|explanation|concept|code|note|summary|support", "span": "full|wide|side|half|third"}},
                {"type": "definition_card", "title": "定义说明", "term": "队列", "definition": "队列是一种只允许在一端插入、另一端删除的线性结构", "intuition": "像排队买票，先来的人先被服务", "formal": "enqueue 在 rear 端插入，dequeue 在 front 端删除", "boundaries": ["只能从 front 删除", "只能从 rear 插入"], "takeaway": "判断队列题时，先找 front 和 rear 的含义", "displayLayout": {"role": "concept", "span": "half"}},
                {"type": "concept_card", "title": "概念拆解", "term": "二叉搜索树", "definition": "每个节点的左子树值都小于节点值，右子树值都大于节点值", "intuition": "每个节点都把搜索范围分成左右两半", "keyPoints": ["左小右大必须对每个节点成立", "中序遍历会得到有序序列"], "examples": [{"label": "正例", "value": "根 8，左子树 3/6，右子树 10/14", "explanation": "每个局部都满足左小右大"}], "nonExamples": [{"label": "反例", "value": "某个右子树节点小于根节点", "explanation": "破坏全局搜索范围"}], "takeaway": "检查 BST 不能只看父子，还要看整棵子树的取值范围", "displayLayout": {"role": "concept", "span": "half"}},
                {"type": "principle_card", "title": "规则说明", "term": "哈希冲突处理", "definition": "不同 key 映射到同一桶时必须用额外策略保存所有元素", "conditions": ["哈希函数可能产生相同下标", "桶内需要能区分原始 key"], "keyPoints": ["链地址法把冲突元素挂到同一 bucket", "开放寻址法继续探测下一个可用位置"], "takeaway": "哈希表不是避免冲突，而是设计冲突后还能正确查找"},
                {"type": "key_takeaway", "title": "关键结论", "takeaway": "看到线性结构操作题，先明确操作端点，再判断元素顺序是否被改变", "keyPoints": ["栈：同一端进出", "队列：一端进一端出", "双端队列：两端都可操作"]},
                {"type": "numbered_points", "title": "队列的 4 个关键点", "lead": "理解队列时先抓住同一组判断维度。", "items": [{"title": "一端插入", "content": "新元素从 rear 端进入。"}, {"title": "一端删除", "content": "旧元素从 front 端离开。"}, {"title": "先进先出", "content": "先进入的元素先被处理。"}, {"title": "指针含义", "content": "front/rear 的定义决定代码是否正确。"}], "conclusion": "判断队列题时，先标 front 和 rear，再判断元素出入顺序。", "displayLayout": {"groupId": "queue-basic", "role": "explanation", "span": "side"}},
                {"type": "reasoning_steps", "title": "为什么循环队列要取模", "question": "rear 到达数组末尾后为什么还能继续入队？", "steps": [{"step": "数组空间固定", "reason": "顺序队列不能无限向右增长", "result": "需要复用前面空出来的位置"}, {"step": "rear 前进后取模", "reason": "取模能把下标映射回 [0, capacity) 范围", "result": "形成首尾相接的逻辑空间"}], "conclusion": "循环队列的关键不是数组变圆，而是下标用取模回绕。"},
                {"type": "concept_breakdown", "title": "栈的概念拆解", "term": "栈", "summary": "栈是一种只允许在同一端插入和删除的线性结构。", "points": [{"name": "操作端", "detail": "所有 push/pop 都发生在 top。"}, {"name": "顺序特征", "detail": "最后进入的元素最先离开。"}, {"name": "典型用途", "detail": "函数调用、括号匹配、DFS 回溯。"}], "rule": "只要题目强调最近加入的先处理，就先考虑栈。"},
                {"type": "ordered_checklist", "title": "写链表删除前先检查", "context": "处理链表删除节点时使用", "items": [{"text": "是否保存了前驱或后继？", "detail": "否则断链后会丢失剩余节点。"}, {"text": "是否处理头节点？", "detail": "删除头节点需要更新 head。"}, {"text": "是否处理空链表？", "detail": "空链表不能访问 next。"}], "reminder": "链表题先保指针，再改连接。"},
                {"type": "code", "title": "代码标题", "language": "c|cpp|java|python|javascript|typescript|go|rust|sql", "content": "纯代码，不要包含 Markdown 代码围栏", "highlights": [2, 3], "focusLine": 3, "displayLayout": {"groupId": "code-explain", "role": "code", "span": "wide"}},
                {"type": "structure", "structureType": "array|stack|queue|linked_list|tree|heap|graph|hash_table|matrix", "title": "结构状态标题", "items": [1, 2, 3], "nodes": [{"id": "A", "label": "A", "depth": 0, "order": 0}], "edges": [{"from": "A", "to": "B", "label": "可选边权"}], "pointers": {"front": 0, "rear": 3, "top": 2}, "activeIndices": [0], "activeIds": ["A"], "visitedIds": ["A"], "frontierIds": ["B"], "pathIds": ["A", "B"], "compareIds": ["1", "4"], "indexBase": 0, "heapType": "max|min", "heapSize": 3, "parentIndex": 0, "childIndices": [1, 2], "swapIndices": [0, 2], "violationIndices": [], "sortedRange": [3, 5], "directed": false, "hashFormula": "hash(key) % m", "columns": 3, "animation": {"action": "insert|delete|enqueue|dequeue|push|pop|compare|swap|search|probe|visit|update|sift_up|sift_down|heapify|extract", "durationMs": 1800, "movingItem": 4, "from": 1, "to": 2, "phases": [{"name": "before", "startRatio": 0, "items": [1, 2, 3], "pointers": {"top": 2}}, {"name": "moving", "startRatio": 0.34, "items": [1, 2, 3], "movingItem": 4, "targetIndex": 3}, {"name": "after", "startRatio": 0.72, "items": [1, 2, 3, 4], "pointers": {"top": 3}}]}, "displayLayout": {"groupId": "queue-basic", "role": "visual", "span": "wide"}},
                {"type": "trace", "traceKind": "array_step|pointer_walk|sort_step|tree_traversal|graph_traversal|heap_adjust", "algorithm": "binary_search|two_pointers|bubble_sort|dfs|bfs|heapify|...", "title": "过程标题", "items": [5, 2, 8], "pointers": {"left": 0, "mid": 1, "right": 2}, "activeIndices": [0, 1], "processedRange": [2, 2], "action": "compare|swap|insert|delete|visit|mark", "explanation": "当前步骤解释", "displayLayout": {"groupId": "trace-explain", "role": "visual", "span": "wide"}},
                {"type": "operation_sequence", "operation": "enqueue|dequeue|push|pop|insert_node|delete_node|heapify", "title": "操作步骤标题", "currentStep": 1, "steps": [{"label": "1", "action": "定位 rear", "description": "找到下一个可插入位置", "before": "rear=2", "after": "rear=3", "why": "rear 始终指向下一个空位"}], "invariant": "队列元素始终位于 [front, rear) 区间。currentStep 从 1 开始计数", "displayLayout": {"groupId": "trace-explain", "role": "explanation", "span": "side"}},
                {"type": "invariant", "title": "不变量标题", "statement": "目标值如果存在，一定在 [left, right] 区间内", "whyItMatters": "每次移动边界都不能排除可能答案", "checkpoints": ["mid 是否已经比较过", "移动边界后目标是否仍可能存在"], "commonMistake": "把可能答案直接跳过", "examples": [{"label": "满足", "value": "left=2,right=5,target 仍在区间", "valid": true, "explanation": "没有丢失可能答案"}]},
                {"type": "complexity", "title": "复杂度标题", "time": "O(n)", "space": "O(1)", "caseHeaders": {"label": "场景", "value": "复杂度", "description": "说明"}, "cases": [{"label": "最坏情况", "value": "O(n)", "description": "原因"}], "reason": "复杂度原因"},
                {"type": "vs_compare", "title": "方法 A vs 方法 B", "left": {"title": "方法 A", "subtitle": "适用场景", "badge": "基础方法", "content": "核心步骤或公式", "strengths": ["优势"], "weaknesses": ["注意点"]}, "right": {"title": "方法 B", "subtitle": "适用场景", "badge": "进阶方法", "content": "核心步骤或公式", "strengths": ["优势"], "weaknesses": ["注意点"]}, "criteria": [{"label": "计算步数", "left": "较多", "right": "较少", "winner": "right", "explanation": "原因"}], "summary": "什么时候优先选哪种方法", "emphasis": "left|right|balanced"}
                """;
    }

    private String compactBlueprintSummary(JsonNode blueprint) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("knowledgeType", text(blueprint, "knowledgeType", ""));
        summary.put("teachingStrategy", text(blueprint, "teachingStrategy", ""));
        summary.put("strategyRationale", text(blueprint, "strategyRationale", ""));
        summary.set("coreMisconceptions", blueprint == null ? objectMapper.createArrayNode() : blueprint.path("coreMisconceptions"));
        summary.set("steps", blueprint == null ? objectMapper.createArrayNode() : blueprint.path("steps"));
        return compactJson(summary);
    }

    private ObjectNode defaultMasteryNode() {
        ObjectNode mastery = objectMapper.createObjectNode();
        mastery.put("level", "progressing");
        mastery.put("levelLabel", "初步掌握");
        mastery.put("hint", "本脚本由分阶段 LLM 流水线根据当前知识点生成。");
        mastery.put("nextSuggestion", "完成本次学习后，根据小测结果安排复习或进入下一个知识点。");
        return mastery;
    }

    private String compactJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception e) {
            return "{}";
        }
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

    private boolean isAllowedCanvasType(String value) {
        return switch (safe(value, "").toLowerCase()) {
            case "concept", "example", "practice", "assessment", "derivation", "summary" -> true;
            default -> false;
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
