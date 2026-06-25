package com.smartorder.edu.learning.service.script;

import com.smartorder.edu.learning.domain.vo.StudyMasteryVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LessonScriptBuilder {

    private final LessonTimelineBuilder timelineBuilder;

    public StudySessionVO build(LessonScriptContext context) {
        String templateCode = resolveTemplateCode(context);
        List<StudyScriptStepVO> nodes = buildNodes(context, templateCode);
        int currentIndex = resolveStartIndex(nodes, context.getRequestedStepId());
        nodes.get(currentIndex).setStatus("in_progress");

        Map<String, StudyTimelineVO> timelinesByStepId = new LinkedHashMap<>();
        for (StudyScriptStepVO node : nodes) {
            timelinesByStepId.put(node.getId(), timelineBuilder.build(node));
        }

        StudySessionVO session = new StudySessionVO();
        session.setSessionId("ss-mock-" + context.getTaskId());
        session.setLessonBundleId("lb-" + templateCode + "-" + context.getTaskId());
        session.setUserId(context.getUserId());
        session.setTaskId(context.getTaskId());
        session.setSpaceId(context.getSpaceId());
        session.setMode(context.getMode());
        session.setStatus("active");
        session.setScriptSteps(nodes);
        session.setCurrentStepId(nodes.get(currentIndex).getId());
        session.setCurrentScene(buildScene(nodes.get(currentIndex), templateCode));
        session.setCurrentTimeline(timelinesByStepId.get(session.getCurrentStepId()));
        session.setTimelinesByStepId(timelinesByStepId);
        session.setMastery(buildMastery(context.getMode(), templateCode));
        return session;
    }

    private String resolveTemplateCode(LessonScriptContext context) {
        if ("breakthrough".equals(context.getMode())) {
            return "math_breakthrough_v1";
        }
        if ("assessment".equals(context.getMode()) || "exam".equals(context.getTaskType())) {
            return "math_diagnostic_v1";
        }
        if ("review".equals(context.getTaskType())) {
            return "adaptive_review_v1";
        }
        if ("practice".equals(context.getTaskType()) || "practice".equals(context.getPathNodeType())) {
            return "guided_practice_v1";
        }
        return "math_concept_default_v1";
    }

    private List<StudyScriptStepVO> buildNodes(LessonScriptContext context, String templateCode) {
        return switch (templateCode) {
            case "math_diagnostic_v1" -> diagnosticNodes(context, templateCode);
            case "math_breakthrough_v1" -> breakthroughNodes(context, templateCode);
            case "adaptive_review_v1" -> reviewNodes(context, templateCode);
            case "guided_practice_v1" -> practiceNodes(context, templateCode);
            default -> defaultConceptNodes(context, templateCode);
        };
    }

    private List<StudyScriptStepVO> defaultConceptNodes(LessonScriptContext context, String templateCode) {
        String taskId = context.getTaskId();
        String topic = topic(context);
        String description = shortDescription(context, "极限定义 · 函数可导性");
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(context, "s1", "review", "concept_review", "回顾：" + topic, description, duration(context, 8), "concept", 1, templateCode));
        nodes.add(node(context, "s2", "example", "worked_example", "例题演示：" + topic, "用一个动态例子拆解核心方法", 10, "example", 2, templateCode));
        nodes.add(node(context, "s3", "practice", "guided_practice", "跟做：" + topic, "边做边判断关键步骤", 10, "practice", 3, templateCode));
        nodes.add(node(context, "s4", "assessment", "diagnostic_quiz", "诊断：" + topic, "用一道易错题判断掌握情况", 8, "assessment", 4, templateCode));
        nodes.add(node(context, "s5", "advanced", "extension", "拓展：" + topic, "根据掌握情况进入提高或补基础", 12, "derivation", 5, templateCode));
        nodes.add(node(context, "s6", "summary", "summary", "总结：" + topic, "Agent 整理本次学习记录", 5, "summary", 6, templateCode));
        return nodes;
    }

    private List<StudyScriptStepVO> diagnosticNodes(LessonScriptContext context, String templateCode) {
        String topic = topic(context);
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(context, "d1", "diagnostic", "diagnostic_quiz", "诊断：" + topic, "先判断是否需要完整讲解", 6, "assessment", 1, templateCode));
        nodes.add(node(context, "d2", "targeted_review", "targeted_review", "定向补弱：" + topic, "只讲诊断暴露出的薄弱点", 8, "concept", 2, templateCode));
        nodes.add(node(context, "d3", "summary", "summary", "诊断结果与下一步", "更新画像并给出学习建议", 4, "summary", 3, templateCode));
        return nodes;
    }

    private List<StudyScriptStepVO> breakthroughNodes(LessonScriptContext context, String templateCode) {
        String topic = topic(context);
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(context, "b1", "diagnose_gap", "diagnostic_quiz", "定位卡点：" + topic, "先判断主要薄弱点", 6, "assessment", 1, templateCode));
        nodes.add(node(context, "b2", "rebuild_foundation", "concept_review", "重建基础：" + topic, shortDescription(context, "回到最小前置概念"), 10, "concept", 2, templateCode));
        nodes.add(node(context, "b3", "guided_practice", "guided_practice", "专项跟做：" + topic, "只练当前薄弱项", 12, "practice", 3, templateCode));
        nodes.add(node(context, "b4", "summary", "summary", "专项突破总结", "记录薄弱项和复习计划", 5, "summary", 4, templateCode));
        return nodes;
    }

    private List<StudyScriptStepVO> reviewNodes(LessonScriptContext context, String templateCode) {
        String topic = topic(context);
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(context, "r1", "recall", "recall_check", "快速回忆：" + topic, "先检查你还记得多少", 4, "assessment", 1, templateCode));
        nodes.add(node(context, "r2", "targeted_review", "targeted_review", "复习薄弱点：" + topic, shortDescription(context, "只复习容易遗忘的关键点"), 8, "concept", 2, templateCode));
        nodes.add(node(context, "r3", "practice", "guided_practice", "复习巩固：" + topic, "用一道题完成巩固", 8, "practice", 3, templateCode));
        nodes.add(node(context, "r4", "summary", "summary", "复习总结", "安排下一次复习触发点", 4, "summary", 4, templateCode));
        return nodes;
    }

    private List<StudyScriptStepVO> practiceNodes(LessonScriptContext context, String templateCode) {
        String topic = topic(context);
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(context, "p1", "warmup", "concept_brief", "题前热身：" + topic, shortDescription(context, "回顾做题所需的最少知识"), 4, "concept", 1, templateCode));
        nodes.add(node(context, "p2", "guided_practice", "guided_practice", "跟做练习：" + topic, "Agent 分步带你完成", duration(context, 10), "practice", 2, templateCode));
        nodes.add(node(context, "p3", "assessment", "diagnostic_quiz", "独立检验：" + topic, "检查是否能独立完成同类题", 6, "assessment", 3, templateCode));
        nodes.add(node(context, "p4", "summary", "summary", "练习总结", "记录错因和下一题建议", 4, "summary", 4, templateCode));
        return nodes;
    }

    private StudyScriptStepVO node(LessonScriptContext context, String suffix, String phase, String nodeType, String title,
                                   String subtitle, Integer estimatedMinutes, String canvasType,
                                   Integer sortOrder, String templateCode) {
        StudyScriptStepVO node = new StudyScriptStepVO();
        node.setId(context.getTaskId() + "-" + suffix);
        node.setPhase(phase);
        node.setNodeType(nodeType);
        node.setTitle(title);
        node.setSubtitle(subtitle);
        node.setEstimatedMinutes(estimatedMinutes);
        node.setStatus("pending");
        node.setCanvasType(canvasType);
        node.setSortOrder(sortOrder);
        node.setMetadata(new LinkedHashMap<>(Map.of(
                "templateCode", templateCode,
                "variableNode", true,
                "taskType", safe(context.getTaskType()),
                "spaceName", safe(context.getSpaceName()),
                "pathNodeType", safe(context.getPathNodeType()),
                "difficultyLevel", context.getDifficultyLevel() == null ? 0 : context.getDifficultyLevel()
        )));
        return node;
    }

    private String topic(LessonScriptContext context) {
        if (hasText(context.getPathNodeTitle())) {
            return context.getPathNodeTitle();
        }
        if (hasText(context.getTaskTitle())) {
            return context.getTaskTitle();
        }
        return "洛必达法则";
    }

    private String shortDescription(LessonScriptContext context, String fallback) {
        if (hasText(context.getPathNodeDescription())) {
            return context.getPathNodeDescription();
        }
        if (hasText(context.getTaskDescription())) {
            return context.getTaskDescription();
        }
        return fallback;
    }

    private Integer duration(LessonScriptContext context, Integer fallback) {
        if (context.getEstimatedDurationMinutes() == null || context.getEstimatedDurationMinutes() <= 0) {
            return fallback;
        }
        return Math.max(3, Math.min(context.getEstimatedDurationMinutes(), 20));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int resolveStartIndex(List<StudyScriptStepVO> nodes, String requestedStepId) {
        if (requestedStepId != null && !requestedStepId.isBlank()) {
            for (int i = 0; i < nodes.size(); i++) {
                if (requestedStepId.equals(nodes.get(i).getId())) {
                    return i;
                }
            }
        }
        return 0;
    }

    private StudySceneVO buildScene(StudyScriptStepVO step, String templateCode) {
        StudySceneVO scene = new StudySceneVO();
        scene.setSceneId(step.getId());
        scene.setSceneType(step.getCanvasType());
        scene.setStepId(step.getId());
        scene.setContent(new LinkedHashMap<>(Map.of(
                "title", step.getTitle(),
                "subtitle", step.getSubtitle(),
                "nodeType", step.getNodeType(),
                "templateCode", templateCode,
                "metadata", step.getMetadata(),
                "mockContentSource", "frontend_compat"
        )));
        return scene;
    }

    private StudyMasteryVO buildMastery(String mode, String templateCode) {
        StudyMasteryVO mastery = new StudyMasteryVO();
        if ("breakthrough".equals(mode)) {
            mastery.setLevel("weak");
            mastery.setLevelLabel("需补基础");
            mastery.setHint("Agent 判断：当前脚本采用专项突破模板，先定位薄弱点再补基础。");
            mastery.setNextSuggestion("建议完成专项题后，再进入默认讲解或测评。");
            return mastery;
        }
        if ("math_diagnostic_v1".equals(templateCode)) {
            mastery.setLevel("unknown");
            mastery.setLevelLabel("待诊断");
            mastery.setHint("Agent 将先通过诊断题判断是否需要完整讲解。");
            mastery.setNextSuggestion("先完成诊断题，再决定是否进入补弱节点。");
            return mastery;
        }

        mastery.setLevel("progressing");
        mastery.setLevelLabel("初步掌握");
        mastery.setHint("Agent 判断：你已掌握基本概念，但在洛必达适用条件上仍需巩固。");
        mastery.setNextSuggestion("完成本次总结后，进入提高题或预约一次专项测评。");
        return mastery;
    }
}
