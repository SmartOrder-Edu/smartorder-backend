package com.smartorder.edu.learning.service.impl;

import com.smartorder.edu.learning.domain.vo.StudyMasteryVO;
import com.smartorder.edu.learning.domain.vo.StudyEventReceiptVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudyScriptStepVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTeachingActionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineCueVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import com.smartorder.edu.learning.dto.StudyEventDTO;
import com.smartorder.edu.learning.dto.StudySessionStartDTO;
import com.smartorder.edu.learning.service.IStudySessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class StudySessionServiceImpl implements IStudySessionService {

    private static final long DEFAULT_SEGMENT_MS = 3500L;

    @Override
    public StudySessionVO startSession(Long userId, StudySessionStartDTO dto) {
        String taskId = normalizeId(dto.getTaskId(), "mock-task");
        String spaceId = dto.getSpaceId();
        String mode = normalizeMode(dto.getMode());

        StudySessionVO session = buildMockSession(userId, taskId, spaceId, mode);
        log.info("[StudySession] mock session started: userId={}, taskId={}, sessionId={}",
                userId, taskId, session.getSessionId());
        return session;
    }

    @Override
    public StudySessionVO getSession(Long userId, String sessionId) {
        String taskId = parseTaskId(sessionId);
        StudySessionVO session = buildMockSession(userId, taskId, null, "learn");
        session.setSessionId(sessionId);
        return session;
    }

    @Override
    public StudyEventReceiptVO recordEvent(Long userId, String sessionId, StudyEventDTO dto) {
        String eventType = dto.getEventType() == null ? "unknown" : dto.getEventType();
        String eventId = "se-" + UUID.randomUUID();
        long acceptedAtMs = System.currentTimeMillis();

        log.info("[StudyEvent] accepted: eventId={}, userId={}, sessionId={}, eventType={}, targetType={}, targetId={}, stepId={}, timelineMs={}",
                eventId,
                userId,
                sessionId,
                eventType,
                dto.getTargetType(),
                dto.getTargetId(),
                dto.getCurrentStepId(),
                dto.getTimelineTimeMs());

        StudyEventReceiptVO receipt = new StudyEventReceiptVO();
        receipt.setEventId(eventId);
        receipt.setSessionId(sessionId);
        receipt.setEventType(eventType);
        receipt.setStatus("accepted");
        receipt.setAcceptedAtMs(acceptedAtMs);
        receipt.setActions(buildTeachingActions(dto));
        return receipt;
    }

    private List<StudyTeachingActionVO> buildTeachingActions(StudyEventDTO dto) {
        if (!"feedback_clicked".equals(dto.getEventType())) {
            return List.of();
        }

        String feedbackType = readPayloadString(dto, "feedbackType", dto.getTargetId());
        StudyTeachingActionVO reply = agentReplyAction(feedbackType);
        List<StudyTeachingActionVO> actions = new ArrayList<>();
        actions.add(reply);

        if ("already_mastered".equals(feedbackType)) {
            actions.add(noteAction("Agent：已标记本步为掌握，可直接进入下一步。"));
        } else if ("skip".equals(feedbackType)) {
            StudyTeachingActionVO skip = new StudyTeachingActionVO();
            skip.setActionId("act-" + UUID.randomUUID());
            skip.setActionType("mark_current_step_skipped");
            skip.setSceneType("script");
            skip.setReason("学生主动选择跳过当前知识点");
            skip.setPayload(new LinkedHashMap<>(Map.of(
                    "message", "已跳过当前知识点。Agent 会在后续会话补一次快速检验。"
            )));
            actions.add(skip);
        }

        return actions;
    }

    private StudyTeachingActionVO agentReplyAction(String feedbackType) {
        StudyTeachingActionVO action = new StudyTeachingActionVO();
        action.setActionId("act-" + UUID.randomUUID());
        action.setActionType("agent_reply");
        action.setSceneType("agent_panel");
        action.setReason("根据学生反馈返回即时教学回应");
        action.setPayload(new LinkedHashMap<>(Map.of(
                "text", feedbackReplyText(feedbackType),
                "markdown", true
        )));
        return action;
    }

    private StudyTeachingActionVO noteAction(String note) {
        StudyTeachingActionVO action = new StudyTeachingActionVO();
        action.setActionId("act-" + UUID.randomUUID());
        action.setActionType("append_agent_note");
        action.setSceneType("canvas");
        action.setReason("反馈触发画布补充提示");
        action.setPayload(new LinkedHashMap<>(Map.of("note", note)));
        return action;
    }

    private String feedbackReplyText(String feedbackType) {
        return switch (feedbackType) {
            case "dont_understand" -> "我换个更直白的说法：洛必达的本质是“用导数比代替函数比”，但前提是这个代替后的极限必须真的存在。";
            case "slower" -> "好的，我把节奏放慢。我们先停在当前这一步，你消化完再继续。";
            case "another_example" -> "换个例子：求 $\\lim_{x \\to 0} \\frac{1-\\cos x}{x^2}$。这是 0/0 型，一次洛必达后得到 $\\frac{1}{2}$。";
            case "draw_diagram" -> "我用流程图解释：\n\n```mermaid\nflowchart TD\n  A[代入是否为 0/0 或 ∞/∞] -->|否| B[不能直接用洛必达]\n  A -->|是| C[导数比极限是否存在]\n  C -->|否| D[不能用洛必达]\n  C -->|是| E[可用洛必达]\n```";
            case "similar_question" -> "再来一道类似题：求 $\\lim_{x \\to 0} \\frac{\\ln(1+x)}{x}$。提示：先判型，再对分子分母分别求导。";
            case "already_mastered" -> "收到，Agent 已标记本知识点为“初步掌握”，后续会减少重复讲解。";
            case "add_review" -> "已加入复习候选：这个知识点后续会通过今日学习再次出现，用来巩固长期记忆。";
            case "skip" -> "已跳过当前知识点。Agent 已记录，会在下次会话开头补一次快速检验。";
            default -> "收到你的反馈，Agent 正在调整讲解。";
        };
    }

    private String readPayloadString(StudyEventDTO dto, String key, String fallback) {
        if (dto.getPayload() == null) {
            return fallback;
        }
        Object value = dto.getPayload().get(key);
        if (value == null) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private StudySessionVO buildMockSession(Long userId, String taskId, String spaceId, String mode) {
        List<StudyScriptStepVO> steps = buildScriptSteps(taskId);
        int currentIndex = "assessment".equals(mode) ? 3 : 0;
        steps.get(currentIndex).setStatus("in_progress");

        Map<String, StudyTimelineVO> timelinesByStepId = new LinkedHashMap<>();
        for (StudyScriptStepVO step : steps) {
            timelinesByStepId.put(step.getId(), buildTimeline(step));
        }

        StudySessionVO session = new StudySessionVO();
        session.setSessionId("ss-mock-" + taskId);
        session.setLessonBundleId("lb-mock-" + taskId);
        session.setUserId(userId);
        session.setTaskId(taskId);
        session.setSpaceId(spaceId);
        session.setMode(mode);
        session.setStatus("active");
        session.setScriptSteps(steps);
        session.setCurrentStepId(steps.get(currentIndex).getId());
        session.setCurrentScene(buildScene(steps.get(currentIndex)));
        session.setCurrentTimeline(timelinesByStepId.get(session.getCurrentStepId()));
        session.setTimelinesByStepId(timelinesByStepId);
        session.setMastery(buildMastery(mode));
        return session;
    }

    private List<StudyScriptStepVO> buildScriptSteps(String taskId) {
        List<StudyScriptStepVO> steps = new ArrayList<>();
        steps.add(step(taskId, "s1", "review", "回顾前置概念", "极限定义 · 函数可导性", 8, "concept"));
        steps.add(step(taskId, "s2", "example", "看一个动态例子", "0/0 型不定式如何化简", 10, "example"));
        steps.add(step(taskId, "s3", "practice", "跟做一道题", "sin x / x 求极限", 10, "practice"));
        steps.add(step(taskId, "s4", "assessment", "系统判断掌握情况", "一道易错题检验", 8, "assessment"));
        steps.add(step(taskId, "s5", "advanced", "进入提高题 / 补基础", "根据掌握情况自适应", 12, "derivation"));
        steps.add(step(taskId, "s6", "summary", "本次总结与画像更新", "Agent 整理本次学习记录", 5, "summary"));
        return steps;
    }

    private StudyScriptStepVO step(String taskId, String suffix, String phase, String title,
                                   String subtitle, Integer estimatedMinutes, String canvasType) {
        StudyScriptStepVO step = new StudyScriptStepVO();
        step.setId(taskId + "-" + suffix);
        step.setPhase(phase);
        step.setTitle(title);
        step.setSubtitle(subtitle);
        step.setEstimatedMinutes(estimatedMinutes);
        step.setStatus("pending");
        step.setCanvasType(canvasType);
        return step;
    }

    private StudyTimelineVO buildTimeline(StudyScriptStepVO step) {
        List<String> segmentTypes = switch (step.getCanvasType()) {
            case "concept" -> List.of("text", "formula", "text", "diagram", "quiz", "text");
            case "example" -> List.of("text", "text", "text", "formula");
            case "practice" -> List.of("text", "quiz", "formula");
            case "assessment" -> List.of("text", "formula", "quiz");
            case "derivation" -> List.of("text", "formula", "formula");
            case "summary" -> List.of("text", "text", "text");
            default -> List.of("text");
        };

        StudyTimelineVO timeline = new StudyTimelineVO();
        timeline.setId("timeline-" + step.getId());
        timeline.setSceneId(step.getId());
        timeline.setClockSource("internal");

        long cursorMs = 0L;
        for (int i = 0; i < segmentTypes.size(); i++) {
            String segmentType = segmentTypes.get(i);
            boolean waitForUser = i == segmentTypes.size() - 1 || "quiz".equals(segmentType) || "diagram".equals(segmentType);
            long durationMs = waitForUser ? 800L : DEFAULT_SEGMENT_MS + i * 400L;

            StudyTimelineCueVO cue = new StudyTimelineCueVO();
            cue.setId(step.getId() + "-seg-" + (i + 1) + "-reveal");
            cue.setType("segment_reveal");
            cue.setStartMs(cursorMs);
            cue.setEndMs(cursorMs + durationMs);
            cue.setPayload(new LinkedHashMap<>(Map.of(
                    "segmentId", step.getId() + "-" + (i + 1),
                    "segmentIndex", i,
                    "segmentType", segmentType,
                    "waitForUser", waitForUser
            )));
            timeline.getCues().add(cue);
            cursorMs += durationMs;
        }

        timeline.setDurationMs(Math.max(cursorMs, 800L));
        timeline.setMetadata(new LinkedHashMap<>(Map.of(
                "source", "backend_mock",
                "stepTitle", step.getTitle(),
                "contractVersion", 1
        )));
        return timeline;
    }

    private StudySceneVO buildScene(StudyScriptStepVO step) {
        StudySceneVO scene = new StudySceneVO();
        scene.setSceneId(step.getId());
        scene.setSceneType(step.getCanvasType());
        scene.setStepId(step.getId());
        scene.setContent(new LinkedHashMap<>(Map.of(
                "title", step.getTitle(),
                "subtitle", step.getSubtitle(),
                "mockContentSource", "frontend_compat"
        )));
        return scene;
    }

    private StudyMasteryVO buildMastery(String mode) {
        StudyMasteryVO mastery = new StudyMasteryVO();
        if ("breakthrough".equals(mode)) {
            mastery.setLevel("weak");
            mastery.setLevelLabel("需补基础");
            mastery.setHint("Agent 判断：连续在适用条件上出错，需要回到极限定义本身。");
            mastery.setNextSuggestion("建议先完成前置概念回顾，再进入专项突破。");
            return mastery;
        }

        mastery.setLevel("progressing");
        mastery.setLevelLabel("初步掌握");
        mastery.setHint("Agent 判断：你已掌握基本概念，但在洛必达适用条件上仍需巩固。");
        mastery.setNextSuggestion("完成本次总结后，进入提高题或预约一次专项测评。");
        return mastery;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "learn";
        }
        return mode;
    }

    private String normalizeId(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String parseTaskId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "mock-task";
        }
        if (sessionId.startsWith("ss-mock-")) {
            return sessionId.substring("ss-mock-".length());
        }
        return sessionId;
    }
}
