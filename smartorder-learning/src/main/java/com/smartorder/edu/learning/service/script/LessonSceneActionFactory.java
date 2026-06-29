package com.smartorder.edu.learning.service.script;

import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LessonSceneActionFactory {

    public Map<String, Object> build(StudyScriptStepVO step, String segmentType,
                                     int segmentIndex, long startMs, long durationMs) {
        String actionType = switch (segmentType) {
            case "formula" -> "formula_write";
            case "diagram" -> "diagram_trace";
            case "quiz" -> "quiz_mount";
            case "pause" -> "narration";
            default -> "text_fade_in";
        };
        String targetType = switch (segmentType) {
            case "formula" -> "formula";
            case "diagram" -> "diagram";
            case "quiz" -> "quiz";
            default -> "segment";
        };
        String instruction = switch (actionType) {
            case "formula_write" -> "按推导顺序书写公式";
            case "diagram_trace" -> "沿节点关系绘制图示";
            case "quiz_mount" -> "挂载互动题并等待学生作答";
            case "narration" -> "展示过渡旁白";
            default -> "逐段淡入文字讲解";
        };

        String targetId = step.getId() + "-" + (segmentIndex + 1);
        return new LinkedHashMap<>(Map.of(
                "actionId", targetId + "-action",
                "actionType", actionType,
                "targetType", targetType,
                "targetId", targetId,
                "layer", "quiz".equals(segmentType) ? "overlay" : "content",
                "startMs", startMs,
                "durationMs", Math.min(Math.max(durationMs, 1200L), 5200L),
                "instruction", instruction,
                "payload", new LinkedHashMap<>(Map.of(
                        "segmentIndex", segmentIndex,
                        "segmentType", segmentType,
                        "stepId", step.getId(),
                        "canvasType", step.getCanvasType(),
                        "nodeType", step.getNodeType()
                ))
        ));
    }
}
