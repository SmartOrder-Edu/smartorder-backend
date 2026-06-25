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
        List<StudyScriptStepVO> nodes = buildNodes(context.getTaskId(), templateCode);
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
        return switch (context.getMode()) {
            case "assessment" -> "math_diagnostic_v1";
            case "breakthrough" -> "math_breakthrough_v1";
            default -> "math_concept_default_v1";
        };
    }

    private List<StudyScriptStepVO> buildNodes(String taskId, String templateCode) {
        return switch (templateCode) {
            case "math_diagnostic_v1" -> diagnosticNodes(taskId, templateCode);
            case "math_breakthrough_v1" -> breakthroughNodes(taskId, templateCode);
            default -> defaultConceptNodes(taskId, templateCode);
        };
    }

    private List<StudyScriptStepVO> defaultConceptNodes(String taskId, String templateCode) {
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(taskId, "s1", "review", "concept_review", "回顾前置概念", "极限定义 · 函数可导性", 8, "concept", 1, templateCode));
        nodes.add(node(taskId, "s2", "example", "worked_example", "看一个动态例子", "0/0 型不定式如何化简", 10, "example", 2, templateCode));
        nodes.add(node(taskId, "s3", "practice", "guided_practice", "跟做一道题", "sin x / x 求极限", 10, "practice", 3, templateCode));
        nodes.add(node(taskId, "s4", "assessment", "diagnostic_quiz", "系统判断掌握情况", "一道易错题检验", 8, "assessment", 4, templateCode));
        nodes.add(node(taskId, "s5", "advanced", "extension", "进入提高题 / 补基础", "根据掌握情况自适应", 12, "derivation", 5, templateCode));
        nodes.add(node(taskId, "s6", "summary", "summary", "本次总结与画像更新", "Agent 整理本次学习记录", 5, "summary", 6, templateCode));
        return nodes;
    }

    private List<StudyScriptStepVO> diagnosticNodes(String taskId, String templateCode) {
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(taskId, "d1", "diagnostic", "diagnostic_quiz", "先做掌握诊断", "用一道易错题判断是否需要讲解", 6, "assessment", 1, templateCode));
        nodes.add(node(taskId, "d2", "targeted_review", "targeted_review", "只补薄弱点", "围绕适用条件做最短讲解", 8, "concept", 2, templateCode));
        nodes.add(node(taskId, "d3", "summary", "summary", "诊断结果与下一步", "更新画像并给出学习建议", 4, "summary", 3, templateCode));
        return nodes;
    }

    private List<StudyScriptStepVO> breakthroughNodes(String taskId, String templateCode) {
        List<StudyScriptStepVO> nodes = new ArrayList<>();
        nodes.add(node(taskId, "b1", "diagnose_gap", "diagnostic_quiz", "定位卡点", "先判断是判型问题还是求导问题", 6, "assessment", 1, templateCode));
        nodes.add(node(taskId, "b2", "rebuild_foundation", "concept_review", "重建前置概念", "回到去心邻域和导数极限", 10, "concept", 2, templateCode));
        nodes.add(node(taskId, "b3", "guided_practice", "guided_practice", "跟做专项题", "只练洛必达适用条件", 12, "practice", 3, templateCode));
        nodes.add(node(taskId, "b4", "summary", "summary", "专项突破总结", "记录薄弱项和复习计划", 5, "summary", 4, templateCode));
        return nodes;
    }

    private StudyScriptStepVO node(String taskId, String suffix, String phase, String nodeType, String title,
                                   String subtitle, Integer estimatedMinutes, String canvasType,
                                   Integer sortOrder, String templateCode) {
        StudyScriptStepVO node = new StudyScriptStepVO();
        node.setId(taskId + "-" + suffix);
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
                "variableNode", true
        )));
        return node;
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
