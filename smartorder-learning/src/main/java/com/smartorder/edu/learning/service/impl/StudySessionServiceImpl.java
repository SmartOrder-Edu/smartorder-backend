package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.entity.StudyEvent;
import com.smartorder.edu.learning.domain.entity.StudySession;
import com.smartorder.edu.learning.domain.entity.StudyTeachingAction;
import com.smartorder.edu.learning.domain.vo.StudyEventReceiptVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTeachingActionVO;
import com.smartorder.edu.learning.dto.StudyEventDTO;
import com.smartorder.edu.learning.dto.StudySessionStartDTO;
import com.smartorder.edu.learning.mapper.StudyEventMapper;
import com.smartorder.edu.learning.mapper.StudySessionMapper;
import com.smartorder.edu.learning.mapper.StudyTeachingActionMapper;
import com.smartorder.edu.learning.service.IStudySessionService;
import com.smartorder.edu.learning.service.script.LessonScriptBuilder;
import com.smartorder.edu.learning.service.script.LessonScriptContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudySessionServiceImpl implements IStudySessionService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final StudySessionMapper studySessionMapper;
    private final StudyEventMapper studyEventMapper;
    private final StudyTeachingActionMapper studyTeachingActionMapper;
    private final LessonScriptBuilder lessonScriptBuilder;

    @Override
    public StudySessionVO startSession(Long userId, StudySessionStartDTO dto) {
        String taskId = normalizeId(dto.getTaskId(), "mock-task");
        String spaceId = dto.getSpaceId();
        String mode = normalizeMode(dto.getMode());

        StudySessionVO session = lessonScriptBuilder.build(new LessonScriptContext(
                userId, taskId, spaceId, mode, dto.getStepId()
        ));
        persistSession(session, dto);
        log.info("[StudySession] mock session started: userId={}, taskId={}, sessionId={}",
                userId, taskId, session.getSessionId());
        return session;
    }

    @Override
    public StudySessionVO getSession(Long userId, String sessionId) {
        String taskId = parseTaskId(sessionId);
        StudySessionVO session = lessonScriptBuilder.build(new LessonScriptContext(
                userId, taskId, null, "learn", null
        ));
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
        persistEvent(userId, sessionId, dto, receipt);
        return receipt;
    }

    private void persistSession(StudySessionVO session, StudySessionStartDTO dto) {
        try {
            StudySession entity = new StudySession();
            entity.setSessionId(session.getSessionId());
            entity.setLessonBundleId(session.getLessonBundleId());
            entity.setUserId(session.getUserId());
            entity.setTaskId(session.getTaskId());
            entity.setSpaceId(session.getSpaceId());
            entity.setMode(session.getMode());
            entity.setStatus(session.getStatus());
            entity.setCurrentStepId(session.getCurrentStepId());
            entity.setScriptSnapshot(toJson(session.getScriptSteps()));
            entity.setCurrentSceneSnapshot(toJson(session.getCurrentScene()));
            entity.setCurrentTimelineSnapshot(toJson(session.getCurrentTimeline()));
            entity.setMasterySnapshot(toJson(session.getMastery()));
            entity.setMetadata(toJson(new LinkedHashMap<>(Map.of(
                    "entrySource", normalizeId(dto.getEntrySource(), "unknown"),
                    "requestedStepId", normalizeId(dto.getStepId(), "")
            ))));
            entity.setStartedAt(LocalDateTime.now());
            entity.setIsDeleted(0);

            StudySession existed = studySessionMapper.selectOne(new QueryWrapper<StudySession>()
                    .eq("session_id", session.getSessionId())
                    .eq("is_deleted", 0)
                    .last("LIMIT 1"));
            if (existed == null) {
                studySessionMapper.insert(entity);
            } else {
                entity.setId(existed.getId());
                studySessionMapper.updateById(entity);
            }
        } catch (Exception e) {
            log.warn("[StudySession] persist skipped: sessionId={}, reason={}",
                    session.getSessionId(), e.getMessage());
        }
    }

    private void persistEvent(Long userId, String sessionId, StudyEventDTO dto, StudyEventReceiptVO receipt) {
        try {
            StudyEvent event = new StudyEvent();
            event.setEventId(receipt.getEventId());
            event.setSessionId(sessionId);
            event.setUserId(userId);
            event.setEventType(receipt.getEventType());
            event.setTargetType(dto.getTargetType());
            event.setTargetId(dto.getTargetId());
            event.setCurrentStepId(dto.getCurrentStepId());
            event.setCurrentTimelineId(dto.getCurrentTimelineId());
            event.setTimelineTimeMs(dto.getTimelineTimeMs());
            event.setClientTimeMs(dto.getClientTimeMs());
            event.setPayload(toJson(dto.getPayload()));
            event.setStatus(receipt.getStatus());
            event.setAcceptedAt(LocalDateTime.now());
            event.setIsDeleted(0);
            studyEventMapper.insert(event);
            persistActions(userId, sessionId, receipt);
        } catch (Exception e) {
            log.warn("[StudyEvent] persist skipped: eventId={}, sessionId={}, reason={}",
                    receipt.getEventId(), sessionId, e.getMessage());
        }
    }

    private void persistActions(Long userId, String sessionId, StudyEventReceiptVO receipt) {
        for (StudyTeachingActionVO action : receipt.getActions()) {
            StudyTeachingAction entity = new StudyTeachingAction();
            entity.setActionId(action.getActionId());
            entity.setEventId(receipt.getEventId());
            entity.setSessionId(sessionId);
            entity.setUserId(userId);
            entity.setActionType(action.getActionType());
            entity.setSceneType(action.getSceneType());
            entity.setReason(action.getReason());
            entity.setPayload(toJson(action.getPayload()));
            entity.setStatus("pending");
            entity.setIsDeleted(0);
            studyTeachingActionMapper.insert(entity);
        }
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

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("[StudySession] json serialize failed: {}", e.getMessage());
            return null;
        }
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
