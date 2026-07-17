package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.LearningRecord;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.domain.entity.LessonBundle;
import com.smartorder.edu.learning.domain.entity.StudyEvent;
import com.smartorder.edu.learning.domain.entity.StudySession;
import com.smartorder.edu.learning.domain.entity.StudyTeachingAction;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.StudyEventReceiptVO;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudySessionCompleteVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTeachingActionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import com.smartorder.edu.learning.dto.StudyEventDTO;
import com.smartorder.edu.learning.dto.StudySessionCompleteDTO;
import com.smartorder.edu.learning.dto.StudySessionStartDTO;
import com.smartorder.edu.learning.dto.StudyTeachingActionStatusDTO;
import com.smartorder.edu.learning.mapper.LearningPathNodeMapper;
import com.smartorder.edu.learning.mapper.LearningRecordMapper;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.mapper.LessonBundleMapper;
import com.smartorder.edu.learning.mapper.StudyEventMapper;
import com.smartorder.edu.learning.mapper.StudySessionMapper;
import com.smartorder.edu.learning.mapper.StudyTeachingActionMapper;
import com.smartorder.edu.learning.mapper.TaskMapper;
import com.smartorder.edu.learning.service.ILessonBundleService;
import com.smartorder.edu.learning.service.IStudySessionService;
import com.smartorder.edu.learning.service.media.LessonMediaAssetService;
import com.smartorder.edu.learning.service.script.LessonScriptContext;
import com.smartorder.edu.learning.service.script.LessonScriptGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudySessionServiceImpl implements IStudySessionService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Set<String> CLIENT_HANDLED_FEEDBACK_TYPES = Set.of(
            "dont_understand",
            "another_example",
            "draw_diagram",
            "similar_question"
    );

    private final StudySessionMapper studySessionMapper;
    private final StudyEventMapper studyEventMapper;
    private final StudyTeachingActionMapper studyTeachingActionMapper;
    private final TaskMapper taskMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final LearningPathNodeMapper learningPathNodeMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final LessonBundleMapper lessonBundleMapper;
    private final LessonScriptGenerationService lessonScriptGenerationService;
    private final ILessonBundleService lessonBundleService;
    private final LessonMediaAssetService lessonMediaAssetService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudySessionVO startSession(Long userId, StudySessionStartDTO dto) {
        StudyEntry entry = resolveStudyEntry(userId, dto);
        String mode = normalizeMode(dto.getMode());
        String sessionId = "ss-" + UUID.randomUUID();
        LessonBundle reusableBundle = selectReusableLessonBundle(userId, entry, mode);

        StudySessionVO session = reusableBundle == null ? null : restoreBundleSession(reusableBundle);
        String bundleId;
        boolean reusedBundle = session != null;
        if (reusedBundle) {
            bundleId = reusableBundle.getBundleId();
            resetSessionProgress(session, dto.getStepId());
        } else {
            bundleId = "lb-" + UUID.randomUUID();
            session = lessonScriptGenerationService.generate(buildScriptContext(
                    userId, entry.taskId(), entry.spaceId(), mode, dto.getStepId()));
        }
        session.setSessionId(sessionId);
        session.setLessonBundleId(bundleId);
        session.setTaskId(entry.taskId());
        session.setPathNodeId(entry.pathNodeId() == null ? null : String.valueOf(entry.pathNodeId()));
        session.setSpaceId(entry.spaceId());
        session.setMode(mode);
        session.setStatus("active");
        lessonMediaAssetService.enrichSession(session);

        if (!reusedBundle) {
            persistLessonBundle(userId, entry, mode, bundleId, session);
        }
        persistSession(session, dto, entry);
        log.info("[StudySession] session started: userId={}, taskId={}, pathNodeId={}, sessionId={}, bundleId={}, reusedBundle={}",
                userId, entry.taskId(), entry.pathNodeId(), sessionId, bundleId, reusedBundle);
        return session;
    }

    @Override
    public StudySessionVO getSession(Long userId, String sessionId) {
        StudySession entity = selectSessionForUser(userId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("学习会话不存在或无权访问: " + sessionId);
        }
        return restoreSession(entity);
    }

    @Override
    public StudyEventReceiptVO recordEvent(Long userId, String sessionId, StudyEventDTO dto) {
        StudySession session = selectSessionForUser(userId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("学习会话不存在或无权访问: " + sessionId);
        }

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
        updateSessionProgressSnapshot(session, dto);
        return receipt;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudySessionCompleteVO completeSession(Long userId, String sessionId, StudySessionCompleteDTO dto) {
        StudySession entity = selectSessionForUser(userId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("学习会话不存在或无权访问: " + sessionId);
        }

        StudySessionVO session = restoreSession(entity);
        CompletionStats stats = summarizeCompletion(entity, session, dto);
        Map<String, Object> summary = buildCompletionSummary(entity, session, dto, stats);

        entity.setStatus("completed");
        entity.setEndedAt(LocalDateTime.now());
        entity.setMetadata(toJson(mergeMetadata(entity.getMetadata(), Map.of("summary", summary))));
        studySessionMapper.updateById(entity);

        completeRealTaskIfNeeded(userId, entity, stats.actualMinutes());
        upsertLearningRecord(userId, entity, dto, stats, summary);
        preheatNextLessonBundle(userId, entity);

        StudySessionCompleteVO vo = new StudySessionCompleteVO();
        vo.setSessionId(sessionId);
        vo.setStatus("completed");
        vo.setActualMinutes(stats.actualMinutes());
        vo.setCompletedSteps(stats.completedSteps());
        vo.setTotalSteps(stats.totalSteps());
        vo.setMasteryLevel(session.getMastery() == null ? "unknown" : session.getMastery().getLevel());
        vo.setNextSuggestion(session.getMastery() == null ? "" : session.getMastery().getNextSuggestion());
        vo.setSummary(summary);
        return vo;
    }

    private void preheatNextLessonBundle(Long userId, StudySession entity) {
        if (entity.getPathNodeId() == null) {
            return;
        }
        try {
            lessonBundleService.preheatNextPathNode(userId, entity.getPathNodeId(), entity.getMode());
        } catch (Exception e) {
            log.warn("[StudySession] next lesson bundle preheat skipped: sessionId={}, pathNodeId={}, reason={}",
                    entity.getSessionId(), entity.getPathNodeId(), e.getMessage());
        }
    }

    @Override
    public void updateTeachingActionStatus(Long userId, String sessionId, String actionId,
                                           StudyTeachingActionStatusDTO dto) {
        StudySession session = selectSessionForUser(userId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("学习会话不存在或无权访问: " + sessionId);
        }
        StudyTeachingAction action = studyTeachingActionMapper.selectOne(new QueryWrapper<StudyTeachingAction>()
                .eq("session_id", sessionId)
                .eq("action_id", actionId)
                .eq("user_id", userId)
                .eq("is_deleted", 0)
                .last("LIMIT 1"));
        if (action == null) {
            throw new IllegalArgumentException("教学动作不存在或无权访问: " + actionId);
        }

        action.setStatus(normalizeActionStatus(dto == null ? null : dto.getStatus()));
        if (dto != null && (dto.getErrorMessage() != null || dto.getPayload() != null)) {
            Map<String, Object> patch = new LinkedHashMap<>();
            if (dto.getErrorMessage() != null && !dto.getErrorMessage().isBlank()) {
                patch.put("errorMessage", dto.getErrorMessage());
            }
            if (dto.getPayload() != null && !dto.getPayload().isEmpty()) {
                patch.put("clientPayload", dto.getPayload());
            }
            action.setPayload(toJson(mergeMetadata(action.getPayload(), patch)));
        }
        studyTeachingActionMapper.updateById(action);
    }

    private LessonBundle selectReusableLessonBundle(Long userId, StudyEntry entry, String mode) {
        QueryWrapper<LessonBundle> query = new QueryWrapper<LessonBundle>()
                .eq("user_id", userId)
                .eq("mode", mode)
                .eq("status", "ready")
                .eq("is_deleted", 0)
                .orderByDesc("create_time")
                .last("LIMIT 1");
        if (entry.pathNodeId() != null) {
            query.eq("path_node_id", entry.pathNodeId());
        } else {
            query.eq("task_id", entry.taskId());
        }
        if (entry.spaceId() != null && !entry.spaceId().isBlank()) {
            query.eq("space_id", entry.spaceId());
        }

        LessonBundle bundle = lessonBundleMapper.selectOne(query);
        if (bundle == null || isBundleExpired(bundle) || bundle.getBundleJson() == null) {
            return null;
        }
        return bundle;
    }

    private boolean isBundleExpired(LessonBundle bundle) {
        return bundle.getExpiresAt() != null && bundle.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private StudySessionVO restoreBundleSession(LessonBundle bundle) {
        StudySessionVO session = fromJson(bundle.getBundleJson(), StudySessionVO.class);
        if (session == null || session.getScriptSteps() == null || session.getScriptSteps().isEmpty()) {
            return null;
        }
        return session;
    }

    private void resetSessionProgress(StudySessionVO session, String requestedStepId) {
        if (session.getScriptSteps() == null || session.getScriptSteps().isEmpty()) {
            return;
        }
        String currentStepId = session.getScriptSteps().get(0).getId();
        if (requestedStepId != null && !requestedStepId.isBlank()) {
            for (var step : session.getScriptSteps()) {
                if (requestedStepId.equals(step.getId())) {
                    currentStepId = requestedStepId;
                    break;
                }
            }
        }

        for (var step : session.getScriptSteps()) {
            step.setStatus(currentStepId.equals(step.getId()) ? "in_progress" : "pending");
        }
        session.setCurrentStepId(currentStepId);
        session.setCurrentScene(resolveCurrentScene(session, currentStepId));
        session.setCurrentTimeline(resolveCurrentTimeline(session, currentStepId));
    }

    private StudyEntry resolveStudyEntry(Long userId, StudySessionStartDTO dto) {
        String requestedTaskId = normalizeId(dto.getTaskId(), "");
        if (isNumeric(requestedTaskId)) {
            Task task = taskMapper.selectById(Long.valueOf(requestedTaskId));
            if (task != null && userId.equals(task.getUserId()) && !isDeleted(task.getIsDeleted())) {
                return new StudyEntry(String.valueOf(task.getId()), task.getPathNodeId(), stringId(task.getSpaceId()));
            }
        }

        String requestedPathNodeId = normalizeId(dto.getPathNodeId(), "");
        if (isNumeric(requestedPathNodeId)) {
            LearningPathNode node = learningPathNodeMapper.selectById(Long.valueOf(requestedPathNodeId));
            if (node == null || isDeleted(node.getIsDeleted())) {
                throw new IllegalArgumentException("学习路径节点不存在: " + requestedPathNodeId);
            }
            LearningSpace space = learningSpaceMapper.selectById(node.getSpaceId());
            if (space == null || !userId.equals(space.getUserId()) || isDeleted(space.getIsDeleted())) {
                throw new IllegalArgumentException("学习路径节点不属于当前用户: " + requestedPathNodeId);
            }
            Task task = findOrCreatePathNodeTask(userId, node, dto);
            return new StudyEntry(String.valueOf(task.getId()), node.getId(), String.valueOf(node.getSpaceId()));
        }

        if (!requestedTaskId.isBlank()) {
            return new StudyEntry(requestedTaskId, null, dto.getSpaceId());
        }
        return new StudyEntry("mock-task", null, dto.getSpaceId());
    }

    private Task findOrCreatePathNodeTask(Long userId, LearningPathNode node, StudySessionStartDTO dto) {
        Task existed = taskMapper.selectOne(new QueryWrapper<Task>()
                .eq("user_id", userId)
                .eq("space_id", node.getSpaceId())
                .eq("path_node_id", node.getId())
                .eq("is_deleted", 0)
                .in("status", List.of("pending", "in_progress"))
                .orderByDesc("create_time")
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }

        Task task = new Task();
        task.setUserId(userId);
        task.setSpaceId(node.getSpaceId());
        task.setPathNodeId(node.getId());
        task.setTitle("学习：" + normalizeId(node.getTitle(), "路径节点"));
        task.setDescription(node.getDescription());
        task.setTaskType("review".equals(dto.getMode()) ? "review" : "daily");
        task.setActionType("learn_script");
        task.setTargetType("path_node");
        task.setTargetId(node.getId());
        task.setPriority(3);
        task.setStatus("pending");
        task.setDueDate(LocalDate.now());
        task.setEstimatedDurationMinutes(node.getEstimatedDurationMinutes() == null
                ? 20 : Math.max(5, node.getEstimatedDurationMinutes()));
        task.setSortOrder(0);
        task.setAiSuggestion("由学习路径节点直接进入学习时自动创建。");
        task.setAgentReason("用户从学习路径节点进入，系统自动创建脚本学习任务。");
        task.setMetadata(toJson(new LinkedHashMap<>(Map.of(
                "source", "path_node_study_entry",
                "entrySource", normalizeId(dto.getEntrySource(), "unknown")
        ))));
        task.setIsDeleted(0);
        taskMapper.insert(task);
        return task;
    }

    private LessonScriptContext buildScriptContext(Long userId, String taskId, String requestedSpaceId,
                                                   String mode, String requestedStepId) {
        LessonScriptContext context = new LessonScriptContext(userId, taskId, requestedSpaceId, mode, requestedStepId);
        if (!isNumeric(taskId)) {
            return context;
        }

        try {
            Task task = taskMapper.selectById(Long.valueOf(taskId));
            if (task == null || !userId.equals(task.getUserId()) || isDeleted(task.getIsDeleted())) {
                return context;
            }

            context.setTaskTitle(task.getTitle());
            context.setTaskDescription(task.getDescription());
            context.setTaskType(task.getTaskType());
            context.setEstimatedDurationMinutes(task.getEstimatedDurationMinutes());
            if (task.getSpaceId() != null) {
                context.setSpaceId(String.valueOf(task.getSpaceId()));
                fillSpaceContext(context, task.getSpaceId(), userId);
            } else if (isNumeric(requestedSpaceId)) {
                fillSpaceContext(context, Long.valueOf(requestedSpaceId), userId);
            }
            if (task.getPathNodeId() != null) {
                fillPathNodeContext(context, task.getPathNodeId());
            }
        } catch (Exception e) {
            log.warn("[StudySession] real task context skipped: taskId={}, reason={}", taskId, e.getMessage());
        }
        return context;
    }

    private void fillSpaceContext(LessonScriptContext context, Long spaceId, Long userId) {
        LearningSpace space = learningSpaceMapper.selectById(spaceId);
        if (space == null || !userId.equals(space.getUserId()) || isDeleted(space.getIsDeleted())) {
            return;
        }
        context.setSpaceName(space.getName());
        context.setSpaceType(space.getType());
    }

    private void fillPathNodeContext(LessonScriptContext context, Long pathNodeId) {
        LearningPathNode node = learningPathNodeMapper.selectById(pathNodeId);
        if (node == null || isDeleted(node.getIsDeleted())) {
            return;
        }
        context.setPathNodeTitle(node.getTitle());
        context.setPathNodeDescription(node.getDescription());
        context.setPathNodeType(node.getNodeType());
        context.setDifficultyLevel(node.getDifficultyLevel());
        if (context.getEstimatedDurationMinutes() == null) {
            context.setEstimatedDurationMinutes(node.getEstimatedDurationMinutes());
        }
    }

    private void persistLessonBundle(Long userId, StudyEntry entry, String mode, String bundleId, StudySessionVO session) {
        LessonBundle bundle = new LessonBundle();
        bundle.setBundleId(bundleId);
        bundle.setUserId(userId);
        bundle.setTaskId(entry.taskId());
        bundle.setPathNodeId(entry.pathNodeId());
        bundle.setSpaceId(entry.spaceId());
        bundle.setMode(mode);
        bundle.setStatus("ready");
        bundle.setVersion(1);
        bundle.setGenerationContext(toJson(new LinkedHashMap<>(Map.of(
                "source", resolveScriptSource(session),
                "taskId", entry.taskId(),
                "pathNodeId", entry.pathNodeId() == null ? "" : entry.pathNodeId(),
                "spaceId", entry.spaceId() == null ? "" : entry.spaceId()
        ))));
        bundle.setBundleJson(toJson(session));
        bundle.setIsDeleted(0);
        lessonBundleMapper.insert(bundle);
    }

    private String resolveScriptSource(StudySessionVO session) {
        if (session == null || session.getScriptSteps() == null || session.getScriptSteps().isEmpty()
                || session.getScriptSteps().get(0).getMetadata() == null) {
            return "lesson_script_generator";
        }
        Object source = session.getScriptSteps().get(0).getMetadata().get("source");
        if (source != null && !String.valueOf(source).isBlank()) {
            return String.valueOf(source);
        }
        Object generator = session.getScriptSteps().get(0).getMetadata().get("generator");
        return generator == null || String.valueOf(generator).isBlank()
                ? "lesson_script_generator"
                : "lesson_script_generator_" + generator;
    }

    private void persistSession(StudySessionVO session, StudySessionStartDTO dto, StudyEntry entry) {
        StudySession entity = new StudySession();
        entity.setSessionId(session.getSessionId());
        entity.setLessonBundleId(session.getLessonBundleId());
        entity.setUserId(session.getUserId());
        entity.setTaskId(session.getTaskId());
        entity.setPathNodeId(entry.pathNodeId());
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
                "requestedStepId", normalizeId(dto.getStepId(), ""),
                "pathNodeId", entry.pathNodeId() == null ? "" : entry.pathNodeId()
        ))));
        entity.setStartedAt(LocalDateTime.now());
        entity.setIsDeleted(0);
        studySessionMapper.insert(entity);
    }

    private void persistEvent(Long userId, String sessionId, StudyEventDTO dto, StudyEventReceiptVO receipt) {
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

    private void updateSessionProgressSnapshot(StudySession session, StudyEventDTO dto) {
        if (dto.getCurrentStepId() == null || dto.getCurrentStepId().isBlank()) {
            return;
        }
        if (!"step_entered".equals(dto.getEventType()) && !"step_completed".equals(dto.getEventType())) {
            return;
        }

        StudySessionVO restored = restoreSession(session);
        String currentStepId = dto.getCurrentStepId();
        boolean changed = false;

        if (restored.getScriptSteps() != null) {
            for (var step : restored.getScriptSteps()) {
                if ("step_completed".equals(dto.getEventType()) && currentStepId.equals(step.getId())) {
                    step.setStatus("completed");
                    changed = true;
                } else if ("step_entered".equals(dto.getEventType())) {
                    if (currentStepId.equals(step.getId()) && !"completed".equals(step.getStatus())) {
                        step.setStatus("in_progress");
                        changed = true;
                    } else if ("in_progress".equals(step.getStatus())) {
                        step.setStatus("pending");
                        changed = true;
                    }
                }
            }
        }

        session.setCurrentStepId(currentStepId);
        session.setScriptSnapshot(toJson(restored.getScriptSteps()));
        session.setCurrentSceneSnapshot(toJson(resolveCurrentScene(restored, currentStepId)));
        session.setCurrentTimelineSnapshot(toJson(resolveCurrentTimeline(restored, currentStepId)));
        if (changed || !currentStepId.equals(session.getCurrentStepId())) {
            studySessionMapper.updateById(session);
        } else {
            studySessionMapper.updateById(session);
        }
    }

    private StudySession selectSessionForUser(Long userId, String sessionId) {
        return studySessionMapper.selectOne(new QueryWrapper<StudySession>()
                .eq("session_id", sessionId)
                .eq("user_id", userId)
                .eq("is_deleted", 0)
                .last("LIMIT 1"));
    }

    private StudySessionVO restoreSession(StudySession entity) {
        StudySessionVO vo = null;
        if (entity.getLessonBundleId() != null) {
            LessonBundle bundle = lessonBundleMapper.selectOne(new QueryWrapper<LessonBundle>()
                    .eq("bundle_id", entity.getLessonBundleId())
                    .eq("user_id", entity.getUserId())
                    .eq("is_deleted", 0)
                    .last("LIMIT 1"));
            if (bundle != null && bundle.getBundleJson() != null) {
                vo = fromJson(bundle.getBundleJson(), StudySessionVO.class);
            }
        }
        if (vo == null) {
            vo = new StudySessionVO();
        }

        vo.setSessionId(entity.getSessionId());
        vo.setLessonBundleId(entity.getLessonBundleId());
        vo.setUserId(entity.getUserId());
        vo.setTaskId(entity.getTaskId());
        vo.setPathNodeId(entity.getPathNodeId() == null ? null : String.valueOf(entity.getPathNodeId()));
        vo.setSpaceId(entity.getSpaceId());
        vo.setMode(entity.getMode());
        vo.setStatus(entity.getStatus());
        vo.setCurrentStepId(entity.getCurrentStepId());

        if (entity.getScriptSnapshot() != null) {
            vo.setScriptSteps(fromJson(entity.getScriptSnapshot(), new TypeReference<>() {}));
        }
        StudySceneVO scene = fromJson(entity.getCurrentSceneSnapshot(), StudySceneVO.class);
        if (scene != null) {
            vo.setCurrentScene(scene);
        } else {
            vo.setCurrentScene(resolveCurrentScene(vo, vo.getCurrentStepId()));
        }
        StudyTimelineVO timeline = fromJson(entity.getCurrentTimelineSnapshot(), StudyTimelineVO.class);
        if (timeline != null) {
            vo.setCurrentTimeline(timeline);
        } else {
            vo.setCurrentTimeline(resolveCurrentTimeline(vo, vo.getCurrentStepId()));
        }
        return vo;
    }

    private StudyTimelineVO resolveCurrentTimeline(StudySessionVO session, String stepId) {
        if (stepId == null || session.getTimelinesByStepId() == null) {
            return null;
        }
        return session.getTimelinesByStepId().get(stepId);
    }

    private StudySceneVO resolveCurrentScene(StudySessionVO session, String stepId) {
        if (stepId == null || session.getScriptSteps() == null) {
            return null;
        }
        if (session.getScenesByStepId() != null && session.getScenesByStepId().get(stepId) != null) {
            return session.getScenesByStepId().get(stepId);
        }
        return session.getScriptSteps().stream()
                .filter(step -> stepId.equals(step.getId()))
                .findFirst()
                .map(step -> {
                    StudySceneVO scene = new StudySceneVO();
                    scene.setSceneId(step.getId());
                    scene.setSceneType(step.getCanvasType());
                    scene.setStepId(step.getId());
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("title", step.getTitle() == null ? "" : step.getTitle());
                    content.put("subtitle", step.getSubtitle() == null ? "" : step.getSubtitle());
                    content.put("nodeType", step.getNodeType() == null ? "" : step.getNodeType());
                    content.put("metadata", step.getMetadata() == null ? Map.of() : step.getMetadata());
                    content.put("source", "session_snapshot");
                    scene.setContent(content);
                    return scene;
                })
                .orElse(null);
    }

    private CompletionStats summarizeCompletion(StudySession entity, StudySessionVO session, StudySessionCompleteDTO dto) {
        List<StudyEvent> events = studyEventMapper.selectList(new QueryWrapper<StudyEvent>()
                .eq("session_id", entity.getSessionId())
                .eq("user_id", entity.getUserId())
                .eq("is_deleted", 0));
        Set<String> completedStepIds = new LinkedHashSet<>();
        int interruptionCount = 0;
        for (StudyEvent event : events) {
            if ("step_completed".equals(event.getEventType()) && event.getTargetId() != null) {
                completedStepIds.add(event.getTargetId());
            }
            if ("pause_clicked".equals(event.getEventType())) {
                interruptionCount++;
            }
        }
        int totalSteps = readPayloadInt(dto.getPayload(), "totalSteps",
                session.getScriptSteps() == null ? 0 : session.getScriptSteps().size());
        int clientCompletedSteps = readPayloadInt(dto.getPayload(), "completedSteps", -1);
        int completedSteps = clientCompletedSteps >= 0
                ? clientCompletedSteps
                : Math.max(completedStepIds.size(), "completed".equals(entity.getStatus()) ? totalSteps : 0);
        Integer requestedMinutes = dto.getActualMinutes();
        int actualMinutes = requestedMinutes != null && requestedMinutes > 0
                ? requestedMinutes : estimateActualMinutes(entity, session);
        return new CompletionStats(actualMinutes, completedSteps, totalSteps, interruptionCount);
    }

    private int estimateActualMinutes(StudySession entity, StudySessionVO session) {
        if (entity.getStartedAt() != null) {
            LocalDateTime end = entity.getEndedAt() == null ? LocalDateTime.now() : entity.getEndedAt();
            long minutes = Duration.between(entity.getStartedAt(), end).toMinutes();
            if (minutes > 0) {
                return (int) Math.min(minutes, 24 * 60);
            }
        }
        if (session.getScriptSteps() == null) {
            return 0;
        }
        return session.getScriptSteps().stream()
                .mapToInt(step -> step.getEstimatedMinutes() == null ? 0 : step.getEstimatedMinutes())
                .sum();
    }

    private Map<String, Object> buildCompletionSummary(StudySession entity, StudySessionVO session,
                                                       StudySessionCompleteDTO dto, CompletionStats stats) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionId", entity.getSessionId());
        summary.put("taskId", entity.getTaskId());
        summary.put("pathNodeId", entity.getPathNodeId());
        summary.put("spaceId", entity.getSpaceId());
        summary.put("actualMinutes", stats.actualMinutes());
        summary.put("completedSteps", stats.completedSteps());
        summary.put("totalSteps", stats.totalSteps());
        summary.put("interruptionCount", stats.interruptionCount());
        summary.put("completedAt", LocalDateTime.now().toString());
        if (session.getMastery() != null) {
            summary.put("masteryLevel", session.getMastery().getLevel());
            summary.put("masteryLabel", session.getMastery().getLevelLabel());
            summary.put("nextSuggestion", session.getMastery().getNextSuggestion());
        }
        if (dto.getPayload() != null && !dto.getPayload().isEmpty()) {
            summary.put("clientPayload", dto.getPayload());
        }
        return summary;
    }

    private void completeRealTaskIfNeeded(Long userId, StudySession entity, int actualMinutes) {
        if (!isNumeric(entity.getTaskId())) {
            return;
        }
        Task task = taskMapper.selectById(Long.valueOf(entity.getTaskId()));
        if (task == null || !userId.equals(task.getUserId()) || isDeleted(task.getIsDeleted())) {
            return;
        }
        task.setStatus("completed");
        task.setActualDurationMinutes(actualMinutes);
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void upsertLearningRecord(Long userId, StudySession entity, StudySessionCompleteDTO dto,
                                      CompletionStats stats, Map<String, Object> summary) {
        if (!isNumeric(entity.getSpaceId())) {
            return;
        }
        Long spaceId = Long.valueOf(entity.getSpaceId());
        LocalDate today = LocalDate.now();
        LearningRecord record = learningRecordMapper.selectOne(new QueryWrapper<LearningRecord>()
                .eq("user_id", userId)
                .eq("space_id", spaceId)
                .eq("record_date", today)
                .eq("is_deleted", 0)
                .last("LIMIT 1"));
        if (record == null) {
            record = new LearningRecord();
            record.setUserId(userId);
            record.setSpaceId(spaceId);
            record.setRecordDate(today);
            record.setStudyDurationMinutes(0);
            record.setTasksCompleted(0);
            record.setTasksPlanned(0);
            record.setInterruptionCount(0);
            record.setIsDeleted(0);
        }
        record.setStudyDurationMinutes(nullSafe(record.getStudyDurationMinutes()) + stats.actualMinutes());
        record.setTasksCompleted(nullSafe(record.getTasksCompleted()) + (isNumeric(entity.getTaskId()) ? 1 : 0));
        record.setTasksPlanned(Math.max(nullSafe(record.getTasksPlanned()), record.getTasksCompleted()));
        record.setInterruptionCount(nullSafe(record.getInterruptionCount()) + stats.interruptionCount());
        record.setFocusLevel(dto.getFocusLevel());
        record.setMood(dto.getMood());
        record.setSelfRating(dto.getSelfRating());
        record.setNotes(dto.getNotes());
        record.setAiReflection(String.valueOf(summary.getOrDefault("nextSuggestion", "本次学习会话已完成。")));
        record.setMetadata(toJson(mergeMetadata(record.getMetadata(), Map.of(
                "latestStudySession", summary
        ))));

        if (record.getId() == null) {
            learningRecordMapper.insert(record);
        } else {
            learningRecordMapper.updateById(record);
        }
    }

    private List<StudyTeachingActionVO> buildTeachingActions(StudyEventDTO dto) {
        if ("quiz_answered".equals(dto.getEventType())) {
            return buildQuizAnsweredActions(dto);
        }
        if (!"feedback_clicked".equals(dto.getEventType())) {
            return List.of();
        }

        String feedbackType = readPayloadString(dto, "feedbackType", dto.getTargetId());
        if (readPayloadBoolean(dto, "clientHandled", false)
                && CLIENT_HANDLED_FEEDBACK_TYPES.contains(feedbackType)) {
            return List.of(feedbackVisualEffectAction(feedbackType, dto));
        }

        StudyTeachingActionVO reply = agentReplyAction(feedbackType);
        List<StudyTeachingActionVO> actions = new ArrayList<>();
        actions.add(reply);
        actions.add(feedbackVisualEffectAction(feedbackType, dto));

        if ("slower".equals(feedbackType)) {
            actions.add(setPlaybackRateAction(0.75));
        } else if ("already_mastered".equals(feedbackType)) {
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

    private List<StudyTeachingActionVO> buildQuizAnsweredActions(StudyEventDTO dto) {
        boolean correct = readPayloadBoolean(dto, "correct", false);
        String answer = readPayloadString(dto, "answer", "");
        List<StudyTeachingActionVO> actions = new ArrayList<>();

        StudyTeachingActionVO reply = new StudyTeachingActionVO();
        reply.setActionId("act-" + UUID.randomUUID());
        reply.setActionType("agent_reply");
        reply.setSceneType("agent_panel");
        reply.setReason(correct ? "学生答题正确，给出正向确认" : "学生答题错误，给出即时诊断");
        reply.setPayload(new LinkedHashMap<>(Map.of(
                "text", correct
                        ? "答对了。Agent 已记录这一步的掌握情况，可以继续推进。"
                        : "这道题先别急着跳过。Agent 已记录你的答案「" + answer + "」，建议回到当前关键条件再看一遍。",
                "markdown", true
        )));
        actions.add(reply);

        if (!correct) {
            StudyTeachingActionVO note = new StudyTeachingActionVO();
            note.setActionId("act-" + UUID.randomUUID());
            note.setActionType("append_agent_note");
            note.setSceneType("canvas");
            note.setReason("答题错误触发画布诊断提示");
            note.setPayload(new LinkedHashMap<>(Map.of(
                    "note", "Agent 诊断：这一步可能混淆了适用条件和计算步骤。先确认判型，再继续推导。"
            )));
            actions.add(note);
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

    private StudyTeachingActionVO feedbackVisualEffectAction(String feedbackType, StudyEventDTO dto) {
        StudyTeachingActionVO action = new StudyTeachingActionVO();
        action.setActionId("act-" + UUID.randomUUID());
        action.setActionType("trigger_feedback_visual_effect");
        action.setSceneType("canvas");
        action.setReason("学生反馈触发画布可见响应");
        action.setPayload(new LinkedHashMap<>(Map.of(
                "type", feedbackType == null ? "" : feedbackType,
                "targetType", dto.getTargetType() == null ? "feedback" : dto.getTargetType(),
                "targetId", dto.getTargetId() == null ? "" : dto.getTargetId(),
                "currentStepId", dto.getCurrentStepId() == null ? "" : dto.getCurrentStepId(),
                "currentTimelineId", dto.getCurrentTimelineId() == null ? "" : dto.getCurrentTimelineId(),
                "timelineTimeMs", dto.getTimelineTimeMs() == null ? 0L : dto.getTimelineTimeMs()
        )));
        return action;
    }

    private StudyTeachingActionVO setPlaybackRateAction(double rate) {
        StudyTeachingActionVO action = new StudyTeachingActionVO();
        action.setActionId("act-" + UUID.randomUUID());
        action.setActionType("set_playback_rate");
        action.setSceneType("timeline");
        action.setReason("学生请求调整讲解速度");
        action.setPayload(new LinkedHashMap<>(Map.of("rate", rate)));
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

    private boolean readPayloadBoolean(StudyEventDTO dto, String key, boolean fallback) {
        if (dto.getPayload() == null || dto.getPayload().get(key) == null) {
            return fallback;
        }
        Object value = dto.getPayload().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalizeActionStatus(String status) {
        if (status == null || status.isBlank()) {
            return "applied";
        }
        return switch (status) {
            case "pending", "applied", "failed" -> status;
            default -> "applied";
        };
    }

    private int readPayloadInt(Map<String, Object> payload, String key, int fallback) {
        if (payload == null || payload.get(key) == null) {
            return fallback;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Map<String, Object> mergeMetadata(String raw, Map<String, Object> patch) {
        Map<String, Object> metadata = raw == null || raw.isBlank()
                ? new LinkedHashMap<>()
                : fromJson(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        metadata.putAll(patch);
        return metadata;
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

    private <T> T fromJson(String value, Class<T> clazz) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, clazz);
        } catch (Exception e) {
            log.warn("[StudySession] json parse failed: type={}, reason={}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            log.warn("[StudySession] json parse failed: reason={}", e.getMessage());
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

    private boolean isNumeric(String value) {
        return value != null && value.matches("\\d+");
    }

    private boolean isDeleted(Integer isDeleted) {
        return isDeleted != null && isDeleted == 1;
    }

    private String stringId(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private record StudyEntry(String taskId, Long pathNodeId, String spaceId) {
    }

    private record CompletionStats(int actualMinutes, int completedSteps, int totalSteps, int interruptionCount) {
    }
}
