package com.smartorder.edu.learning.service.script;

import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineCueVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LessonTimelineBuilder {

    private static final long DEFAULT_SEGMENT_MS = 3500L;

    private final LessonSceneActionFactory sceneActionFactory;

    public StudyTimelineVO build(StudyScriptStepVO step) {
        List<String> segmentTypes = segmentTypes(step);

        StudyTimelineVO timeline = new StudyTimelineVO();
        timeline.setId("timeline-" + step.getId());
        timeline.setSceneId(step.getId());
        timeline.setClockSource("internal");

        long cursorMs = 0L;
        for (int i = 0; i < segmentTypes.size(); i++) {
            String segmentType = segmentTypes.get(i);
            boolean waitForUser = i == segmentTypes.size() - 1 || "quiz".equals(segmentType) || "diagram".equals(segmentType);
            long durationMs = waitForUser ? 800L : DEFAULT_SEGMENT_MS + i * 400L;
            Map<String, Object> sceneAction = sceneActionFactory.build(step, segmentType, i, cursorMs, durationMs);

            StudyTimelineCueVO revealCue = new StudyTimelineCueVO();
            revealCue.setId(step.getId() + "-seg-" + (i + 1) + "-reveal");
            revealCue.setType("segment_reveal");
            revealCue.setStartMs(cursorMs);
            revealCue.setEndMs(cursorMs + durationMs);
            revealCue.setPayload(new LinkedHashMap<>(Map.of(
                    "segmentId", step.getId() + "-" + (i + 1),
                    "segmentIndex", i,
                    "segmentType", segmentType,
                    "waitForUser", waitForUser,
                    "sceneAction", sceneAction
            )));
            timeline.getCues().add(revealCue);

            StudyTimelineCueVO sceneActionCue = new StudyTimelineCueVO();
            sceneActionCue.setId(step.getId() + "-seg-" + (i + 1) + "-scene-action");
            sceneActionCue.setType("scene_action");
            sceneActionCue.setStartMs(cursorMs);
            sceneActionCue.setEndMs(cursorMs + Math.min(durationMs, 2400L));
            sceneActionCue.setPayload(sceneAction);
            timeline.getCues().add(sceneActionCue);
            cursorMs += durationMs;
        }

        timeline.setDurationMs(Math.max(cursorMs, 800L));
        timeline.setMetadata(new LinkedHashMap<>(Map.of(
                "source", "script_builder",
                "stepTitle", step.getTitle(),
                "nodeType", step.getNodeType(),
                "contractVersion", 2,
                "capabilities", List.of("segment_reveal", "scene_action")
        )));
        return timeline;
    }

    private List<String> segmentTypes(StudyScriptStepVO step) {
        return switch (step.getCanvasType()) {
            case "concept" -> List.of("text", "formula", "text", "diagram", "quiz", "text");
            case "example" -> List.of("text", "text", "text", "formula");
            case "practice" -> List.of("text", "quiz", "formula");
            case "assessment" -> List.of("text", "formula", "quiz");
            case "derivation" -> List.of("text", "formula", "formula");
            case "summary" -> List.of("text", "text", "text");
            default -> List.of("text");
        };
    }
}
