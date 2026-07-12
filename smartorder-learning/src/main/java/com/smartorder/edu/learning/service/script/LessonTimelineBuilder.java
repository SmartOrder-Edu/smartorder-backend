package com.smartorder.edu.learning.service.script;

import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineCueVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LessonTimelineBuilder {

    private static final long DEFAULT_SEGMENT_MS = 3500L;
    private static final long MIN_SEGMENT_MS = 1200L;
    private static final long MAX_SEGMENT_MS = 30000L;
    private static final long CHINESE_CHAR_MS = 220L;

    private final LessonSceneActionFactory sceneActionFactory;

    public StudyTimelineVO build(StudyScriptStepVO step) {
        return build(step, segmentTypes(step));
    }

    public StudyTimelineVO build(StudyScriptStepVO step, StudySceneVO scene) {
        List<SegmentTimelineSpec> specs = segmentSpecsFromScene(step, scene);
        if (specs.isEmpty()) {
            return build(step);
        }
        return buildFromSpecs(step, specs, "script_builder_scene");
    }

    public StudyTimelineVO build(StudyScriptStepVO step, List<String> segmentTypes) {
        List<String> effectiveSegmentTypes = segmentTypes == null || segmentTypes.isEmpty()
                ? segmentTypes(step)
                : segmentTypes;

        List<SegmentTimelineSpec> specs = new ArrayList<>();
        for (int i = 0; i < effectiveSegmentTypes.size(); i++) {
            String segmentType = effectiveSegmentTypes.get(i);
            boolean waitForUser = i == effectiveSegmentTypes.size() - 1 || waitsForUser(segmentType);
            long durationMs = waitForUser ? 1800L : DEFAULT_SEGMENT_MS + i * 400L;
            specs.add(new SegmentTimelineSpec(
                    step.getId() + "-" + (i + 1),
                    segmentType,
                    "",
                    durationMs,
                    waitForUser,
                    List.of(),
                    Map.of()
            ));
        }
        return buildFromSpecs(step, specs, "script_builder");
    }

    private StudyTimelineVO buildFromSpecs(StudyScriptStepVO step, List<SegmentTimelineSpec> specs, String source) {
        StudyTimelineVO timeline = new StudyTimelineVO();
        timeline.setId("timeline-" + step.getId());
        timeline.setSceneId(step.getId());
        timeline.setClockSource("internal");

        long cursorMs = 0L;
        for (int i = 0; i < specs.size(); i++) {
            SegmentTimelineSpec spec = specs.get(i);
            String segmentType = spec.type();
            boolean waitForUser = spec.waitForUser();
            long durationMs = clampDuration(spec.durationMs());
            Map<String, Object> sceneAction = sceneActionFactory.build(step, segmentType, i, cursorMs, durationMs);
            sceneAction.put("actionId", spec.id() + "-action");
            sceneAction.put("targetId", spec.id());
            sceneAction.put("startMs", cursorMs);
            sceneAction.put("durationMs", Math.min(Math.max(durationMs, 1200L), 5200L));
            Object actionPayload = sceneAction.get("payload");
            if (actionPayload instanceof Map<?, ?> payloadMap) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payloadMap.forEach((key, value) -> payload.put(String.valueOf(key), value));
                payload.put("segmentId", spec.id());
                payload.put("speechStartMs", cursorMs);
                payload.put("speechEndMs", cursorMs + durationMs);
                payload.putAll(spec.actionPayload());
                sceneAction.put("payload", payload);
            }

            StudyTimelineCueVO revealCue = new StudyTimelineCueVO();
            revealCue.setId(spec.id() + "-reveal");
            revealCue.setType("segment_reveal");
            revealCue.setStartMs(cursorMs);
            revealCue.setEndMs(cursorMs + durationMs);
            revealCue.setPayload(new LinkedHashMap<>(Map.of(
                    "segmentId", spec.id(),
                    "segmentIndex", i,
                    "segmentType", segmentType,
                    "waitForUser", false,
                    "waitForUserAtEnd", waitForUser,
                    "speechText", spec.speechText(),
                    "speechStartMs", cursorMs,
                    "speechEndMs", cursorMs + durationMs,
                    "sceneAction", sceneAction
            )));
            timeline.getCues().add(revealCue);

            StudyTimelineCueVO sceneActionCue = new StudyTimelineCueVO();
            sceneActionCue.setId(spec.id() + "-scene-action");
            sceneActionCue.setType("scene_action");
            sceneActionCue.setStartMs(cursorMs);
            sceneActionCue.setEndMs(cursorMs + Math.min(durationMs, 2400L));
            sceneActionCue.setPayload(sceneAction);
            timeline.getCues().add(sceneActionCue);

            appendBoardRevealCues(timeline, spec, i, cursorMs, durationMs);

            if (waitForUser) {
                StudyTimelineCueVO pauseCue = new StudyTimelineCueVO();
                pauseCue.setId(spec.id() + "-wait");
                pauseCue.setType("pause");
                pauseCue.setStartMs(cursorMs + durationMs);
                pauseCue.setEndMs(cursorMs + durationMs);
                pauseCue.setPayload(new LinkedHashMap<>(Map.of(
                        "segmentId", spec.id(),
                        "segmentIndex", i,
                        "segmentType", segmentType,
                        "waitForUser", true,
                        "reason", waitsForUser(segmentType) ? "interaction" : "manual_continue"
                )));
                timeline.getCues().add(pauseCue);
            }
            cursorMs += durationMs;
        }

        timeline.setDurationMs(Math.max(cursorMs, 800L));
        timeline.setMetadata(new LinkedHashMap<>(Map.of(
                "source", source,
                "stepTitle", step.getTitle(),
                "nodeType", step.getNodeType(),
                "contractVersion", 2,
                "capabilities", List.of(
                        "segment_reveal",
                        "scene_action",
                        "pause_at_segment_end",
                        "board_block_action_payload",
                        "board_block_reveal",
                        "board_item_reveal"
                )
        )));
        return timeline;
    }

    @SuppressWarnings("unchecked")
    private List<SegmentTimelineSpec> segmentSpecsFromScene(StudyScriptStepVO step, StudySceneVO scene) {
        if (scene == null || scene.getContent() == null) {
            return List.of();
        }
        Object teachingFlow = scene.getContent().get("teachingFlow");
        if (!(teachingFlow instanceof Map<?, ?> flow)) {
            return List.of();
        }
        Object rawSegments = flow.get("segments");
        if (!(rawSegments instanceof List<?> segments)) {
            return List.of();
        }

        List<SegmentTimelineSpec> specs = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Object item = segments.get(i);
            if (!(item instanceof Map<?, ?> segment)) {
                continue;
            }
            String id = text(segment.get("id"), step.getId() + "-" + (i + 1));
            String type = text(segment.get("type"), "explain");
            String speechText = speechText(segment);
            int autoAdvanceMs = intValue(segment.get("autoAdvanceMs"), 0);
            boolean waitForUser = autoAdvanceMs <= 0 || waitsForUser(type);
            long durationMs = Math.max(autoAdvanceMs, estimateSpeechDurationMs(speechText));
            List<Map<String, Object>> boardBlocks = boardBlocks(segment);
            specs.add(new SegmentTimelineSpec(
                    id,
                    type,
                    speechText,
                    durationMs,
                    waitForUser,
                    boardBlocks,
                    boardActionPayload(boardBlocks)
            ));
        }
        return specs;
    }

    private List<Map<String, Object>> boardBlocks(Map<?, ?> segment) {
        Object rawBlocks = segment.get("boardBlocks");
        if (!(rawBlocks instanceof List<?> blocks) || blocks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : blocks) {
            if (!(item instanceof Map<?, ?> block)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            block.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            result.add(normalized);
        }
        return result;
    }

    private Map<String, Object> boardActionPayload(List<Map<String, Object>> blocks) {
        if (blocks.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            copyPayloadField(block, payload, "type", "boardBlockType");
            copyPayloadField(block, payload, "id", "boardBlockId");
            copyPayloadField(block, payload, "language", "language");
            copyPayloadField(block, payload, "term", "term");
            copyPayloadField(block, payload, "definition", "definition");
            copyPayloadField(block, payload, "intuition", "intuition");
            copyPayloadField(block, payload, "formal", "formal");
            copyPayloadField(block, payload, "keyPoints", "keyPoints");
            copyPayloadField(block, payload, "boundaries", "boundaries");
            copyPayloadField(block, payload, "conditions", "conditions");
            copyPayloadField(block, payload, "examples", "examples");
            copyPayloadField(block, payload, "nonExamples", "nonExamples");
            copyPayloadField(block, payload, "takeaway", "takeaway");
            copyPayloadField(block, payload, "focusLine", "focusLine");
            copyPayloadField(block, payload, "highlights", "highlights");
            copyPayloadField(block, payload, "structureType", "structureType");
            copyPayloadField(block, payload, "items", "items");
            copyPayloadField(block, payload, "nodes", "nodes");
            copyPayloadField(block, payload, "edges", "edges");
            copyPayloadField(block, payload, "pointers", "pointers");
            copyPayloadField(block, payload, "activeIndices", "activeIndices");
            copyPayloadField(block, payload, "activeIds", "activeIds");
            copyPayloadField(block, payload, "visitedIds", "visitedIds");
            copyPayloadField(block, payload, "frontierIds", "frontierIds");
            copyPayloadField(block, payload, "pathIds", "pathIds");
            copyPayloadField(block, payload, "compareIds", "compareIds");
            copyPayloadField(block, payload, "indexBase", "indexBase");
            copyPayloadField(block, payload, "heapType", "heapType");
            copyPayloadField(block, payload, "heapSize", "heapSize");
            copyPayloadField(block, payload, "parentIndex", "parentIndex");
            copyPayloadField(block, payload, "childIndices", "childIndices");
            copyPayloadField(block, payload, "swapIndices", "swapIndices");
            copyPayloadField(block, payload, "violationIndices", "violationIndices");
            copyPayloadField(block, payload, "sortedRange", "sortedRange");
            copyPayloadField(block, payload, "rootId", "rootId");
            copyPayloadField(block, payload, "directed", "directed");
            copyPayloadField(block, payload, "displayLayout", "displayLayout");
            copyPayloadField(block, payload, "layout", "layout");
            copyPayloadField(block, payload, "animation", "animation");
            copyPayloadField(block, payload, "traceKind", "traceKind");
            copyPayloadField(block, payload, "algorithm", "algorithm");
            copyPayloadField(block, payload, "processedRange", "processedRange");
            copyPayloadField(block, payload, "action", "action");
            copyPayloadField(block, payload, "explanation", "explanation");
            copyPayloadField(block, payload, "operation", "operation");
            copyPayloadField(block, payload, "steps", "steps");
            copyPayloadField(block, payload, "currentStep", "currentStep");
            copyPayloadField(block, payload, "invariant", "invariant");
            copyPayloadField(block, payload, "statement", "statement");
            copyPayloadField(block, payload, "whyItMatters", "whyItMatters");
            copyPayloadField(block, payload, "checkpoints", "checkpoints");
            copyPayloadField(block, payload, "commonMistake", "commonMistake");
            copyPayloadField(block, payload, "time", "time");
            copyPayloadField(block, payload, "space", "space");
            copyPayloadField(block, payload, "caseHeaders", "caseHeaders");
            copyPayloadField(block, payload, "cases", "cases");
            copyPayloadField(block, payload, "reason", "reason");
            copyPayloadField(block, payload, "left", "left");
            copyPayloadField(block, payload, "right", "right");
            copyPayloadField(block, payload, "criteria", "criteria");
            copyPayloadField(block, payload, "summary", "summary");
            copyPayloadField(block, payload, "emphasis", "emphasis");
        }
        return payload;
    }

    private void appendBoardRevealCues(StudyTimelineVO timeline, SegmentTimelineSpec spec, int segmentIndex,
                                       long segmentStartMs, long durationMs) {
        List<Map<String, Object>> blocks = spec.boardBlocks();
        if (blocks.isEmpty()) {
            return;
        }

        long blockIntervalMs = revealInterval(durationMs, blocks.size());
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            Map<String, Object> block = blocks.get(blockIndex);
            String blockId = text(block.get("id"), spec.id() + "-block-" + blockIndex);
            String blockType = text(block.get("type"), "markdown");
            long blockStartMs = Math.min(segmentStartMs + durationMs - 1L, segmentStartMs + blockIndex * blockIntervalMs);

            StudyTimelineCueVO blockCue = new StudyTimelineCueVO();
            blockCue.setId(spec.id() + "-block-" + blockIndex + "-reveal");
            blockCue.setType("board_block_reveal");
            blockCue.setStartMs(blockStartMs);
            blockCue.setEndMs(blockStartMs);
            blockCue.setPayload(new LinkedHashMap<>(Map.of(
                    "segmentId", spec.id(),
                    "segmentIndex", segmentIndex,
                    "blockId", blockId,
                    "blockIndex", blockIndex,
                    "blockType", blockType
            )));
            timeline.getCues().add(blockCue);

            appendBoardItemRevealCues(timeline, spec, segmentIndex, block, blockId, blockIndex, blockStartMs, blockIntervalMs);
        }
    }

    private void appendBoardItemRevealCues(StudyTimelineVO timeline, SegmentTimelineSpec spec, int segmentIndex,
                                           Map<String, Object> block, String blockId, int blockIndex,
                                           long blockStartMs, long blockWindowMs) {
        String itemKind = revealItemKind(block);
        if (itemKind.isBlank()) {
            return;
        }
        Object rawItems = block.get(itemKind);
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
            return;
        }

        long itemIntervalMs = revealInterval(Math.max(blockWindowMs, 900L), items.size());
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            long itemStartMs = blockStartMs + Math.min(blockWindowMs - 1L, itemIndex * itemIntervalMs);
            StudyTimelineCueVO itemCue = new StudyTimelineCueVO();
            itemCue.setId(spec.id() + "-block-" + blockIndex + "-item-" + itemIndex + "-reveal");
            itemCue.setType("board_item_reveal");
            itemCue.setStartMs(itemStartMs);
            itemCue.setEndMs(itemStartMs);
            itemCue.setPayload(new LinkedHashMap<>(Map.of(
                    "segmentId", spec.id(),
                    "segmentIndex", segmentIndex,
                    "blockId", blockId,
                    "blockIndex", blockIndex,
                    "itemIndex", itemIndex,
                    "itemKind", itemKind
            )));
            timeline.getCues().add(itemCue);
        }
    }

    private long revealInterval(long durationMs, int count) {
        if (count <= 1) {
            return Math.max(600L, durationMs);
        }
        return Math.max(600L, durationMs / count);
    }

    private String revealItemKind(Map<String, Object> block) {
        String type = normalizeBlockType(text(block.get("type"), ""));
        if ("reasoning_steps".equals(type) || "operation_sequence".equals(type)) {
            return block.get("steps") instanceof List<?> ? "steps" : "";
        }
        if ("concept_breakdown".equals(type)) {
            if (block.get("points") instanceof List<?>) {
                return "points";
            }
            return block.get("items") instanceof List<?> ? "items" : "";
        }
        if ("numbered_points".equals(type) || "ordered_checklist".equals(type)) {
            if (block.get("items") instanceof List<?>) {
                return "items";
            }
            return block.get("points") instanceof List<?> ? "points" : "";
        }
        if ("invariant".equals(type)) {
            return block.get("checkpoints") instanceof List<?> ? "checkpoints" : "";
        }
        return "";
    }

    private String normalizeBlockType(String type) {
        return type == null ? "" : type.trim().toLowerCase().replace('-', '_').replace(' ', '_');
    }

    private void copyPayloadField(Map<?, ?> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        target.putIfAbsent(targetKey, value);
    }

    private String speechText(Map<?, ?> segment) {
        String teacherTalk = text(segment.get("teacherTalk"), "");
        if (!teacherTalk.isBlank()) {
            return teacherTalk;
        }
        String commentary = text(segment.get("agentCommentary"), "");
        if (!commentary.isBlank()) {
            return commentary;
        }
        String markdown = text(segment.get("markdown"), "");
        if (!markdown.isBlank()) {
            return markdown;
        }
        return text(segment.get("boardContent"), "");
    }

    private long estimateSpeechDurationMs(String speechText) {
        int length = speechText == null ? 0 : speechText.trim().length();
        if (length <= 0) {
            return 1800L;
        }
        return Math.max(1800L, Math.min(MAX_SEGMENT_MS, length * CHINESE_CHAR_MS));
    }

    private long clampDuration(long durationMs) {
        return Math.max(MIN_SEGMENT_MS, Math.min(MAX_SEGMENT_MS, durationMs));
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private List<String> segmentTypes(StudyScriptStepVO step) {
        return switch (step.getCanvasType()) {
            case "concept" -> List.of("hook", "recall", "explain", "board_write", "teacher_question", "summary");
            case "example" -> List.of("hook", "worked_example", "board_write", "student_try", "summary");
            case "practice" -> List.of("recall", "student_try", "diagnosis", "summary");
            case "assessment" -> List.of("teacher_question", "student_try", "diagnosis");
            case "derivation" -> List.of("explain", "formula", "board_write", "teacher_question");
            case "summary" -> List.of("summary", "mistake_warning", "transition");
            default -> List.of("explain");
        };
    }

    private boolean waitsForUser(String segmentType) {
        return "quiz".equals(segmentType)
                || "diagram".equals(segmentType)
                || "teacher_question".equals(segmentType)
                || "student_try".equals(segmentType)
                || "self_check".equals(segmentType);
    }

    private record SegmentTimelineSpec(
            String id,
            String type,
            String speechText,
            long durationMs,
            boolean waitForUser,
            List<Map<String, Object>> boardBlocks,
            Map<String, Object> actionPayload
    ) {
    }
}
