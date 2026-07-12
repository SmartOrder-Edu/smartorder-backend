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
            case "formula" -> "formula_step_write";
            case "board_write", "worked_example" -> "board_write";
            case "diagram" -> "diagram_trace";
            case "quiz", "teacher_question", "student_try", "self_check" -> "ask_question";
            case "mistake_warning" -> "show_mistake";
            case "diagnosis" -> "correct_mistake";
            case "hook", "transition" -> "teacher_speak";
            case "summary" -> "summary_pin";
            default -> "text_fade_in";
        };
        String targetType = switch (segmentType) {
            case "formula" -> "formula";
            case "board_write", "worked_example" -> "board";
            case "diagram" -> "diagram";
            case "quiz", "teacher_question", "student_try", "self_check" -> "quiz";
            default -> "segment";
        };
        String instruction = switch (actionType) {
            case "formula_step_write" -> "按推导顺序书写公式";
            case "board_write" -> "像老师板书一样逐步写出关键内容";
            case "diagram_trace" -> "沿节点关系绘制图示";
            case "ask_question" -> "抛出课堂提问并等待学生思考或作答";
            case "show_mistake" -> "展示常见错误并高亮问题位置";
            case "correct_mistake" -> "对学生答案或常见误区进行诊断纠正";
            case "teacher_speak" -> "展示老师导入或过渡旁白";
            case "summary_pin" -> "固定本段结论和可复用方法";
            default -> "逐段淡入文字讲解";
        };

        String targetId = step.getId() + "-" + (segmentIndex + 1);
        return new LinkedHashMap<>(Map.of(
                "actionId", targetId + "-action",
                "actionType", actionType,
                "targetType", targetType,
                "targetId", targetId,
                "layer", isOverlay(segmentType) ? "overlay" : "content",
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

    private boolean isOverlay(String segmentType) {
        return "quiz".equals(segmentType)
                || "teacher_question".equals(segmentType)
                || "student_try".equals(segmentType)
                || "self_check".equals(segmentType);
    }
}
