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
        Map<String, StudySceneVO> scenesByStepId = new LinkedHashMap<>();
        for (StudyScriptStepVO node : nodes) {
            timelinesByStepId.put(node.getId(), timelineBuilder.build(node));
            scenesByStepId.put(node.getId(), buildScene(node, templateCode));
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
        session.setCurrentScene(scenesByStepId.get(session.getCurrentStepId()));
        session.setCurrentTimeline(timelinesByStepId.get(session.getCurrentStepId()));
        session.setScenesByStepId(scenesByStepId);
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
        scene.setContent(buildSceneContent(step, templateCode));
        return scene;
    }

    private Map<String, Object> buildSceneContent(StudyScriptStepVO step, String templateCode) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", step.getCanvasType());
        content.put("markdown", markdownFor(step));
        content.put("teachingFlow", buildTeachingFlow(step));
        content.put("metadata", new LinkedHashMap<>(Map.of(
                "templateCode", templateCode,
                "nodeType", safe(step.getNodeType()),
                "source", "lesson_script_builder_v2"
        )));

        switch (step.getCanvasType()) {
            case "example" -> content.put("exampleSteps", exampleSteps(step));
            case "practice", "assessment" -> content.put("question", questionFor(step));
            default -> {
            }
        }
        return content;
    }

    private String markdownFor(StudyScriptStepVO step) {
        String title = safe(step.getTitle());
        String subtitle = safe(step.getSubtitle());
        return switch (step.getCanvasType()) {
            case "concept" -> "# " + title + "\n\n"
                    + subtitle + "\n\n"
                    + "本节先建立学习目标所需的最小前置概念。Agent 会按“概念判定 → 关键条件 → 小测确认”的顺序推进。\n\n"
                    + "> 重点：先确认定义和适用条件，再进入例题。";
            case "example" -> "# " + title + "\n\n"
                    + "通过一个可拆解的例子理解 `" + subtitle + "`。\n\n"
                    + "Agent 会先判断题型，再展示中间步骤，最后给出可复用的解题模板。";
            case "practice" -> "# " + title + "\n\n"
                    + "请跟着 Agent 完成这道题。先判断条件，再写步骤，最后提交答案。";
            case "assessment" -> "# " + title + "\n\n"
                    + "这一步用于判断你是否真正掌握当前知识点。请先独立作答，再查看 Agent 诊断。";
            case "derivation" -> "# " + title + "\n\n"
                    + "在基础方法之上，Agent 会补充更高效的替代思路，帮助你形成方法选择能力。";
            case "summary" -> "# " + title + "\n\n"
                    + "Agent 会整理本次学习的完成情况、薄弱点和下一步建议。";
            default -> "# " + title + "\n\n" + subtitle;
        };
    }

    private List<Map<String, Object>> exampleSteps(StudyScriptStepVO step) {
        return List.of(
                new LinkedHashMap<>(Map.of(
                        "id", step.getId() + "-ex-1",
                        "label", "判定题型",
                        "markdown", "先识别当前任务的核心条件：`" + safe(step.getSubtitle()) + "`。"
                )),
                new LinkedHashMap<>(Map.of(
                        "id", step.getId() + "-ex-2",
                        "label", "拆解步骤",
                        "markdown", "把问题拆成“已知条件、目标结论、可用方法”三部分，避免直接套公式。"
                )),
                new LinkedHashMap<>(Map.of(
                        "id", step.getId() + "-ex-3",
                        "label", "形成模板",
                        "markdown", "把本题方法沉淀为同类题模板：先判定条件，再选择方法，最后验证结果。"
                ))
        );
    }

    private Map<String, Object> questionFor(StudyScriptStepVO step) {
        boolean assessment = "assessment".equals(step.getCanvasType());
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", step.getId() + "-q1");
        question.put("prompt", assessment ? "判断当前方法是否适用" : "完成当前步骤的小练习");
        question.put("body", assessment
                ? "结合本步骤主题，判断：如果关键前提不满足，是否可以直接套用当前方法？请输入“可以”或“不可以”。"
                : "请写出当前知识点最关键的适用条件。");
        question.put("answer", assessment ? "不可以" : "先满足适用条件");
        question.put("hint", "先看题目是否满足本步骤强调的关键条件。");
        question.put("errorExplanation", "这一步的核心不是计算速度，而是先确认适用条件。条件不满足时，需要换方法或先变形。");
        return question;
    }

    private Map<String, Object> buildTeachingFlow(StudyScriptStepVO step) {
        List<String> segmentTypes = segmentTypes(step);
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < segmentTypes.size(); i++) {
            String segmentType = segmentTypes.get(i);
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("id", step.getId() + "-" + (i + 1));
            segment.put("type", segmentType);
            segment.put("markdown", segmentMarkdown(step, segmentType, i));
            segment.put("autoAdvanceMs", ("quiz".equals(segmentType) || i == segmentTypes.size() - 1) ? 0 : 3600 + i * 400);
            segment.put("agentCommentary", segmentCommentary(step, segmentType, i));
            if ("quiz".equals(segmentType)) {
                segment.put("quiz", inlineQuiz(step));
            }
            if ("diagram".equals(segmentType)) {
                segment.put("focusTarget", new LinkedHashMap<>(Map.of(
                        "selector", ".mermaid-block",
                        "effect", "glow-border",
                        "scroll", "smooth"
                )));
            }
            segments.add(segment);
        }
        return new LinkedHashMap<>(Map.of(
                "segments", segments,
                "currentSegmentIndex", 0,
                "isActive", false,
                "isPaused", false
        ));
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

    private String segmentMarkdown(StudyScriptStepVO step, String segmentType, int index) {
        String title = safe(step.getTitle());
        return switch (segmentType) {
            case "formula" -> "## 关键表达\n\n围绕 **" + title + "**，先确认条件，再执行方法。\n\n"
                    + "$$\\text{条件成立} \\Rightarrow \\text{选择当前方法} \\Rightarrow \\text{验证结果}$$";
            case "diagram" -> "```mermaid\nflowchart TD\n  A[阅读题目] --> B[判断关键条件]\n  B -->|满足| C[使用当前方法]\n  B -->|不满足| D[换方法或先变形]\n  C --> E[验证结果]\n```";
            case "quiz" -> "";
            default -> "## " + (index + 1) + ". " + title + "\n\n"
                    + safe(step.getSubtitle()) + "\n\n"
                    + "Agent 会把这一步拆成可执行的小动作，帮助你减少跳步。";
        };
    }

    private String segmentCommentary(StudyScriptStepVO step, String segmentType, int index) {
        return switch (segmentType) {
            case "formula" -> "注意公式不是第一步，先确认条件是否成立。";
            case "diagram" -> "看这张流程图，核心分叉点是条件判定。";
            case "quiz" -> "这里停一下，用一道小题确认你是否抓住关键条件。";
            default -> index == 0
                    ? "我们开始学习：" + safe(step.getTitle())
                    : "继续拆解这一步，先把判断依据说清楚。";
        };
    }

    private Map<String, Object> inlineQuiz(StudyScriptStepVO step) {
        return new LinkedHashMap<>(Map.of(
                "id", step.getId() + "-inline-q1",
                "prompt", "使用当前方法前，最先要确认什么？",
                "answerType", "single_select",
                "options", List.of(
                        new LinkedHashMap<>(Map.of("label", "关键适用条件是否满足", "value", "a")),
                        new LinkedHashMap<>(Map.of("label", "答案看起来是否简单", "value", "b")),
                        new LinkedHashMap<>(Map.of("label", "是否能直接套公式", "value", "c"))
                ),
                "correctAnswer", "a",
                "explanation", "正确。先确认适用条件，再选择方法，是本步骤最重要的判断。"
        ));
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
