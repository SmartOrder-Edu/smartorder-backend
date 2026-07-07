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
            StudySceneVO scene = buildScene(node, templateCode);
            scenesByStepId.put(node.getId(), scene);
            timelinesByStepId.put(node.getId(), timelineBuilder.build(node, scene));
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
            segment.put("teacherTalk", teacherTalk(step, segmentType, i));
            segment.put("boardContent", boardContent(step, segmentType, i));
            segment.put("boardBlocks", boardBlocks(step, segmentType, i));
            segment.put("teachingIntent", teachingIntent(segmentType));
            segment.put("emotionTone", emotionTone(segmentType));
            segment.put("autoAdvanceMs", (isInteractiveSegment(segmentType) || i == segmentTypes.size() - 1) ? 0 : 3600 + i * 400);
            segment.put("agentCommentary", segmentCommentary(step, segmentType, i));
            if (isInteractiveSegment(segmentType)) {
                segment.put("quiz", inlineQuiz(step));
                segment.put("interaction", inlineQuiz(step));
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
            case "concept" -> List.of("hook", "recall", "explain", "board_write", "teacher_question", "summary");
            case "example" -> List.of("hook", "worked_example", "board_write", "student_try", "summary");
            case "practice" -> List.of("recall", "student_try", "diagnosis", "summary");
            case "assessment" -> List.of("teacher_question", "student_try", "diagnosis");
            case "derivation" -> List.of("explain", "formula", "board_write", "teacher_question");
            case "summary" -> List.of("summary", "mistake_warning", "transition");
            default -> List.of("explain");
        };
    }

    private String segmentMarkdown(StudyScriptStepVO step, String segmentType, int index) {
        String title = safe(step.getTitle());
        return switch (segmentType) {
            case "formula" -> "## 关键表达\n\n围绕 **" + title + "**，先确认条件，再执行方法。\n\n"
                    + "$$\\text{条件成立} \\Rightarrow \\text{选择当前方法} \\Rightarrow \\text{验证结果}$$";
            case "hook" -> "## 先看一个问题\n\n如果一上来就套方法，很容易忽略 **" + title + "** 的关键条件。";
            case "recall" -> "## 先回忆\n\n学习 **" + title + "** 前，先确认你是否记得它依赖的定义、条件和常见判定方式。";
            case "board_write" -> "## 板书\n\n1. 先写出题目给出的条件\n2. 圈出必须验证的关键点\n3. 再选择可用方法";
            case "worked_example" -> "## 例题分镜\n\n先判定题型，再拆解条件，最后把步骤沉淀成可复用模板。";
            case "teacher_question", "student_try" -> "";
            case "diagnosis" -> "## 诊断\n\n如果刚才答错，通常不是不会算，而是跳过了条件判断。我们先把错因拆开。";
            case "mistake_warning" -> "## 常见误区\n\n把“看起来像”当成“条件满足”，是学习 **" + title + "** 时最容易出现的问题。";
            case "summary", "transition" -> "## 小结\n\n把本段方法压缩成一句话：先判断条件，再选择方法，最后验证结果。";
            case "diagram" -> "```mermaid\nflowchart TD\n  A[阅读题目] --> B[判断关键条件]\n  B -->|满足| C[使用当前方法]\n  B -->|不满足| D[换方法或先变形]\n  C --> E[验证结果]\n```";
            case "quiz" -> "";
            default -> "## " + (index + 1) + ". " + title + "\n\n"
                    + safe(step.getSubtitle()) + "\n\n"
                    + "Agent 会把这一步拆成可执行的小动作，帮助你减少跳步。";
        };
    }

    private String teacherTalk(StudyScriptStepVO step, String segmentType, int index) {
        String title = safe(step.getTitle());
        if (isDataStructureTopic(step)) {
            return switch (segmentType) {
                case "board_write" -> "我们用队列状态来观察这一步。先看 front 和 rear 的位置，再看代码为什么只移动 rear。";
                case "worked_example" -> "这里不要只看结果，要盯住每一步操作改变了哪些位置。数据结构题的关键就是状态变化。";
                case "diagnosis" -> "如果刚才没跟上，通常不是概念本身难，而是指针、下标或边界位置看错了。";
                case "mistake_warning" -> "数据结构里最容易错的是边界：空、满、头尾指针和下标是否越界，必须单独检查。";
                case "summary" -> "最后把这一段压缩成三个问题：结构是什么，操作改了哪里，复杂度为什么是这样。";
                default -> "我们从结构状态入手学习：" + title + "。先看图，再看代码，最后分析复杂度。";
            };
        }
        return switch (segmentType) {
            case "hook" -> "我们先不急着看定义。你可以先想一下，为什么同样是“" + title + "”，有的人一套方法就错？今天我们要解决的就是这个判断问题。";
            case "recall" -> "在正式讲之前，先把前置知识叫醒。后面每一步都不是凭感觉做，而是依赖这些条件来判断。";
            case "explain" -> "现在进入核心讲解。我会先说结论，再把它拆成几个可执行的小判断。";
            case "board_write" -> "你看黑板上这三行，第一行写条件，第二行写判断，第三行才是方法。顺序不要反。";
            case "worked_example" -> "我们用一个例子走一遍。注意我不是直接算，而是先判断这个例子到底属于哪一类。";
            case "teacher_question" -> "这里停一下。如果现在让你决定下一步，你会先检查什么？";
            case "student_try" -> "这一小步交给你试一下。先不用追求完整答案，只要说出最关键的判断依据。";
            case "diagnosis" -> "如果你刚才选错了，问题大概率出在跳过了条件。我们把这个错因拆开看。";
            case "mistake_warning" -> "这一点是高频误区。很多同学不是不会公式，而是把条件没满足的情况也拿来套。";
            case "summary" -> "最后收束一下，把这一步变成以后可以复用的方法。";
            case "transition" -> "接下来我们把刚才的方法迁移到下一步，看看它是否还能成立。";
            case "formula" -> "公式不是第一步。先确认条件成立，再让公式上场。";
            case "diagram" -> "看这张图，真正重要的是中间那个分叉：条件满足还是不满足。";
            default -> index == 0 ? "我们开始学习：" + title : "继续拆解这一步，先把判断依据说清楚。";
        };
    }

    private String boardContent(StudyScriptStepVO step, String segmentType, int index) {
        String title = safe(step.getTitle());
        return switch (segmentType) {
            case "teacher_question", "student_try" -> "问题：使用当前方法前，最先要确认什么？";
            case "diagnosis" -> "错因定位：\n\n- 是否跳过了条件？\n- 是否把形式相似当成方法适用？\n- 是否验证了结果？";
            case "mistake_warning" -> "易错点：条件未满足时，不能直接套用当前方法。";
            default -> segmentMarkdown(step, segmentType, index).isBlank()
                    ? "围绕 **" + title + "** 做一次关键判断。"
                    : segmentMarkdown(step, segmentType, index);
        };
    }

    private List<Map<String, Object>> boardBlocks(StudyScriptStepVO step, String segmentType, int index) {
        if (isDataStructureTopic(step)) {
            return dataStructureBoardBlocks(step, segmentType);
        }
        return switch (segmentType) {
            case "diagram" -> List.of(boardBlock(
                    "diagram",
                    "条件判断流程",
                    null,
                    "flowchart TD\n  A[阅读题目] --> B[判断关键条件]\n  B -->|满足| C[使用当前方法]\n  B -->|不满足| D[换方法或先变形]\n  C --> E[验证结果]",
                    "info"
            ));
            case "formula" -> List.of(boardBlock(
                    "formula",
                    "关键表达",
                    null,
                    "$$\\text{条件成立} \\Rightarrow \\text{选择当前方法} \\Rightarrow \\text{验证结果}$$",
                    "info"
            ));
            case "diagnosis" -> List.of(boardBlock(
                    "callout",
                    "错因定位",
                    null,
                    "- 是否跳过了条件？\n- 是否把形式相似当成方法适用？\n- 是否验证了结果？",
                    "warning"
            ));
            case "mistake_warning" -> List.of(boardBlock(
                    "callout",
                    "高频误区",
                    null,
                    "条件未满足时，不能直接套用当前方法。",
                    "warning"
            ));
            case "teacher_question", "student_try" -> List.of(boardBlock(
                    "callout",
                    "课堂提问",
                    null,
                    "使用当前方法前，最先要确认什么？",
                    "info"
            ));
            default -> List.of(boardBlock(
                    "markdown",
                    "板书",
                    null,
                    boardContent(step, segmentType, index),
                    "summary".equals(segmentType) ? "success" : "info"
            ));
        };
    }

    private List<Map<String, Object>> dataStructureBoardBlocks(StudyScriptStepVO step, String segmentType) {
        return switch (segmentType) {
            case "board_write" -> List.of(
                    structureBlock(
                            "队列当前状态",
                            "queue",
                            List.of(10, 20, 30, 40),
                            new LinkedHashMap<>(Map.of("front", 0, "rear", 4)),
                            List.of(3),
                            "front 指向队头元素，rear 指向下一个可插入位置。",
                            queueEnqueueAnimation()
                    ),
                    codeBlock(
                            "队列入队代码",
                            "c",
                            "void enqueue(Queue* q, int val) {\n"
                                    + "    if (q->rear == q->capacity) return;\n"
                                    + "    q->data[q->rear++] = val;\n"
                                    + "}",
                            List.of(2, 3),
                            3,
                            "写入 rear 位置后，rear 后移一格。"
                    ),
                    complexityBlock(
                            "入队复杂度",
                            "O(1)",
                            "O(1)",
                            "入队只判断队满、写入一个位置并移动 rear，不需要遍历队列。"
                    )
            );
            case "worked_example" -> List.of(
                    treeBlock(
                            "二叉树遍历状态",
                            List.of(
                                    node("A", "A", 0, 0),
                                    node("B", "B", 1, 0),
                                    node("C", "C", 1, 1),
                                    node("D", "D", 2, 0),
                                    node("E", "E", 2, 1)
                            ),
                            List.of(edge("A", "B"), edge("A", "C"), edge("B", "D"), edge("B", "E")),
                            List.of("B"),
                            List.of("A"),
                            List.of("A", "B", "E"),
                            "遍历树时要分清当前节点、已经访问的节点，以及接下来要走的路径。"
                    ),
                    heapBlock(
                            "最大堆的数组映射",
                            List.of(90, 72, 65, 44, 18, 31),
                            List.of(1, 4),
                            List.of("1", "4"),
                            "堆本质上常用数组存储，父子关系由下标计算得到。"
                    ),
                    traceBlock(
                            "排序过程：比较相邻元素",
                            "bubble_sort",
                            List.of(5, 2, 8, 1),
                            List.of(0, 1),
                            List.of(3, 3),
                            "compare",
                            "比较 5 和 2，发现顺序不对，下一步需要交换。"
                    ),
                    structureBlock(
                            "链表节点关系",
                            "linked_list",
                            List.of(12, 24, 36),
                            new LinkedHashMap<>(Map.of("head", 0)),
                            List.of(1),
                            "链表查找时需要沿 next 指针逐个访问，不能像数组一样直接按下标跳转。"
                    )
            );
            case "diagnosis", "mistake_warning" -> List.of(boardBlock(
                    "callout",
                    "边界检查",
                    null,
                    "- 空结构：front == rear 或 top == -1\n"
                            + "- 满结构：rear 到达 capacity 或栈顶到达容量上限\n"
                            + "- 指针移动：先读写，再移动，顺序必须明确",
                    "warning"
            ));
            case "summary", "transition" -> List.of(
                    graphBlock(
                            "图遍历状态",
                            List.of(
                                    node("A", "A", 0, 0),
                                    node("B", "B", 0, 0),
                                    node("C", "C", 0, 0),
                                    node("D", "D", 0, 0)
                            ),
                            List.of(edge("A", "B"), edge("A", "C"), edge("B", "D"), edge("C", "D")),
                            List.of("B"),
                            List.of("A"),
                            List.of("C"),
                            List.of("A", "B", "D"),
                            false,
                            "图遍历时要同时维护已访问集合和待访问集合，避免重复访问。"
                    ),
                    complexityBlock(
                            "方法总结",
                            "看具体操作",
                            "看额外存储",
                            "判断复杂度时先问：有没有遍历元素？有没有额外开数组、栈或队列？"
                    ),
                    boardBlock(
                            "markdown",
                            "复盘清单",
                            null,
                            "1. 先画结构状态\n2. 再标出关键指针或下标\n3. 最后解释代码和复杂度",
                            "success"
                    )
            );
            case "teacher_question", "student_try" -> List.of(boardBlock(
                    "callout",
                    "课堂提问",
                    null,
                    "如果执行一次入队，真正发生变化的是元素区、front，还是 rear？",
                    "info"
            ));
            default -> List.of(boardBlock(
                    "markdown",
                    "结构化理解",
                    null,
                    "学习 `" + safe(step.getTitle()) + "` 时，先画出结构状态，再分析每一步操作改变了什么。",
                    "info"
            ));
        };
    }

    private Map<String, Object> codeBlock(String title, String language, String content,
                                          List<Integer> highlights, int focusLine, String caption) {
        Map<String, Object> block = boardBlock("code", title, language, content, "info");
        block.put("highlights", highlights);
        block.put("focusLine", focusLine);
        block.put("caption", caption);
        return block;
    }

    private Map<String, Object> structureBlock(String title, String structureType, List<?> items,
                                               Map<String, ?> pointers, List<Integer> activeIndices,
                                               String caption) {
        return structureBlock(title, structureType, items, pointers, activeIndices, caption, null);
    }

    private Map<String, Object> structureBlock(String title, String structureType, List<?> items,
                                               Map<String, ?> pointers, List<Integer> activeIndices,
                                               String caption, Map<String, Object> animation) {
        Map<String, Object> block = boardBlock("structure", title, null, caption, "info");
        block.put("structureType", structureType);
        block.put("items", items);
        block.put("pointers", pointers);
        block.put("activeIndices", activeIndices);
        block.put("caption", caption);
        if (animation != null && !animation.isEmpty()) {
            block.put("animation", animation);
        }
        return block;
    }

    private Map<String, Object> queueEnqueueAnimation() {
        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("action", "enqueue");
        animation.put("durationMs", 2200);
        animation.put("movingItem", 40);
        animation.put("from", "rear");
        animation.put("to", 3);
        animation.put("previous", queueState(List.of(10, 20, 30), 0, 3));
        animation.put("next", queueState(List.of(10, 20, 30, 40), 0, 4));
        animation.put("phases", List.of(
                queuePhase(
                        "before",
                        0,
                        List.of(10, 20, 30),
                        0,
                        3,
                        null,
                        null,
                        null,
                        null,
                        "入队前：front 指向队头，rear 指向下一个可插入位置。"
                ),
                queuePhase(
                        "moving",
                        0.34,
                        List.of(10, 20, 30),
                        0,
                        3,
                        40,
                        3,
                        null,
                        List.of("rear"),
                        "写入：把 40 放到 rear 指向的位置。"
                ),
                queuePhase(
                        "after",
                        0.72,
                        List.of(10, 20, 30, 40),
                        0,
                        4,
                        null,
                        null,
                        null,
                        null,
                        "入队后：rear 后移一格，继续指向下一个可插入位置。"
                )
        ));
        return animation;
    }

    private Map<String, Object> queueState(List<?> items, int front, int rear) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("items", items);
        state.put("pointers", queuePointers(front, rear));
        return state;
    }

    private Map<String, Object> queuePointers(int front, int rear) {
        Map<String, Object> pointers = new LinkedHashMap<>();
        pointers.put("front", front);
        pointers.put("rear", rear);
        return pointers;
    }

    private Map<String, Object> queuePhase(String name, double startRatio, List<?> items,
                                           int front, int rear, Object movingItem,
                                           Integer targetIndex, Integer sourceIndex,
                                           List<String> highlightPointers, String narrationHint) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("name", name);
        phase.put("startRatio", startRatio);
        phase.put("items", items);
        phase.put("pointers", queuePointers(front, rear));
        if (movingItem != null) {
            phase.put("movingItem", movingItem);
        }
        if (targetIndex != null) {
            phase.put("targetIndex", targetIndex);
        }
        if (sourceIndex != null) {
            phase.put("sourceIndex", sourceIndex);
        }
        if (highlightPointers != null && !highlightPointers.isEmpty()) {
            phase.put("highlightPointers", highlightPointers);
        }
        phase.put("narrationHint", narrationHint);
        return phase;
    }

    private Map<String, Object> treeBlock(String title, List<Map<String, Object>> nodes,
                                          List<Map<String, Object>> edges, List<String> activeIds,
                                          List<String> visitedIds, List<String> pathIds, String caption) {
        Map<String, Object> block = boardBlock("structure", title, null, caption, "info");
        block.put("structureType", "tree");
        block.put("nodes", nodes);
        block.put("edges", edges);
        block.put("activeIds", activeIds);
        block.put("visitedIds", visitedIds);
        block.put("pathIds", pathIds);
        block.put("caption", caption);
        return block;
    }

    private Map<String, Object> heapBlock(String title, List<?> items, List<Integer> activeIndices,
                                          List<String> compareIds, String caption) {
        Map<String, Object> block = boardBlock("structure", title, null, caption, "info");
        block.put("structureType", "heap");
        block.put("items", items);
        block.put("activeIndices", activeIndices);
        block.put("compareIds", compareIds);
        block.put("caption", caption);
        return block;
    }

    private Map<String, Object> graphBlock(String title, List<Map<String, Object>> nodes,
                                           List<Map<String, Object>> edges, List<String> activeIds,
                                           List<String> visitedIds, List<String> frontierIds,
                                           List<String> pathIds, boolean directed, String caption) {
        Map<String, Object> block = boardBlock("structure", title, null, caption, "info");
        block.put("structureType", "graph");
        block.put("nodes", nodes);
        block.put("edges", edges);
        block.put("activeIds", activeIds);
        block.put("visitedIds", visitedIds);
        block.put("frontierIds", frontierIds);
        block.put("pathIds", pathIds);
        block.put("directed", directed);
        block.put("caption", caption);
        return block;
    }

    private Map<String, Object> node(String id, String label, int depth, int order) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "label", label,
                "depth", depth,
                "order", order
        ));
    }

    private Map<String, Object> edge(String from, String to) {
        return new LinkedHashMap<>(Map.of(
                "from", from,
                "to", to
        ));
    }

    private Map<String, Object> traceBlock(String title, String algorithm, List<?> items,
                                           List<Integer> activeIndices, List<Integer> processedRange,
                                           String action, String explanation) {
        Map<String, Object> block = boardBlock("trace", title, null, explanation, "info");
        block.put("algorithm", algorithm);
        block.put("items", items);
        block.put("activeIndices", activeIndices);
        block.put("processedRange", processedRange);
        block.put("action", action);
        block.put("explanation", explanation);
        return block;
    }

    private Map<String, Object> complexityBlock(String title, String time, String space, String reason) {
        Map<String, Object> block = boardBlock("complexity", title, null, reason, "info");
        block.put("time", time);
        block.put("space", space);
        block.put("cases", List.of(
                new LinkedHashMap<>(Map.of(
                        "label", "核心判断",
                        "value", time,
                        "description", reason
                ))
        ));
        block.put("reason", reason);
        return block;
    }

    private Map<String, Object> boardBlock(String type, String title, String language, String content, String tone) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        block.put("title", title);
        if (language != null && !language.isBlank()) {
            block.put("language", language);
        }
        block.put("content", content);
        block.put("tone", tone);
        return block;
    }

    private boolean isDataStructureTopic(StudyScriptStepVO step) {
        String text = (safe(step.getTitle()) + " " + safe(step.getSubtitle()) + " " + safe(step.getNodeType())).toLowerCase();
        String[] keywords = {
                "数据结构", "算法", "数组", "顺序表", "链表", "单链表", "双链表",
                "栈", "队列", "树", "二叉树", "堆", "图", "哈希", "散列",
                "排序", "查找", "遍历", "递归", "动态规划",
                "data structure", "algorithm", "array", "list", "linked list",
                "stack", "queue", "tree", "heap", "graph", "hash",
                "sort", "search", "traversal", "recursion"
        };
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String teachingIntent(String segmentType) {
        return switch (segmentType) {
            case "hook" -> "制造问题感，让学生知道本节课要解决什么。";
            case "recall" -> "激活前置知识，降低后续理解负担。";
            case "teacher_question", "student_try" -> "通过停顿和作答检查学生是否真的跟上。";
            case "diagnosis", "mistake_warning" -> "暴露并纠正常见误区。";
            case "summary" -> "沉淀可复用方法。";
            default -> "推进课堂讲解。";
        };
    }

    private String emotionTone(String segmentType) {
        return switch (segmentType) {
            case "hook" -> "curious";
            case "teacher_question", "student_try" -> "encourage";
            case "diagnosis", "mistake_warning" -> "warning";
            case "summary", "transition" -> "summary";
            default -> "serious";
        };
    }

    private boolean isInteractiveSegment(String segmentType) {
        return "quiz".equals(segmentType)
                || "teacher_question".equals(segmentType)
                || "student_try".equals(segmentType)
                || "self_check".equals(segmentType);
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
