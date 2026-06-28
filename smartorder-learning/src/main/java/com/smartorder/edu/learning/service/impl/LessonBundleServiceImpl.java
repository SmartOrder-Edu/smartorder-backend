package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.LearningPathEdge;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.domain.entity.LessonBundle;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LessonBundleStatusVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.dto.LessonBundleGenerateDTO;
import com.smartorder.edu.learning.mapper.LearningPathEdgeMapper;
import com.smartorder.edu.learning.mapper.LearningPathNodeMapper;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.mapper.LessonBundleMapper;
import com.smartorder.edu.learning.mapper.TaskMapper;
import com.smartorder.edu.learning.service.ILessonBundleService;
import com.smartorder.edu.learning.service.script.LessonScriptContext;
import com.smartorder.edu.learning.service.script.LessonScriptGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonBundleServiceImpl implements ILessonBundleService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int DEFAULT_EXPIRES_DAYS = 7;

    private final LessonBundleMapper lessonBundleMapper;
    private final TaskMapper taskMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final LearningPathNodeMapper learningPathNodeMapper;
    private final LearningPathEdgeMapper learningPathEdgeMapper;
    private final LessonScriptGenerationService lessonScriptGenerationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LessonBundleStatusVO generateBundle(Long userId, LessonBundleGenerateDTO dto) {
        BundleTarget target = resolveTarget(userId, dto);
        String mode = normalizeMode(dto.getMode());
        boolean forceRegenerate = Boolean.TRUE.equals(dto.getForceRegenerate());

        if (!forceRegenerate) {
            LessonBundle existed = selectLatestBundle(userId, target.taskId(), target.pathNodeId(), target.spaceId(), mode);
            if (isReusable(existed)) {
                return toStatusVO(existed);
            }
        }

        LessonScriptContext context = buildScriptContext(userId, target, mode);
        String bundleId = "lb-" + UUID.randomUUID();
        StudySessionVO session = lessonScriptGenerationService.generate(context);
        session.setSessionId("preview-" + bundleId);
        session.setLessonBundleId(bundleId);
        session.setTaskId(target.taskId());
        session.setPathNodeId(target.pathNodeId() == null ? null : String.valueOf(target.pathNodeId()));
        session.setSpaceId(target.spaceId());
        session.setMode(mode);
        session.setStatus("bundle_ready");

        LessonBundle bundle = new LessonBundle();
        bundle.setBundleId(bundleId);
        bundle.setUserId(userId);
        bundle.setTaskId(target.taskId());
        bundle.setPathNodeId(target.pathNodeId());
        bundle.setSpaceId(target.spaceId());
        bundle.setMode(mode);
        bundle.setStatus("ready");
        bundle.setVersion(resolveNextVersion(userId, target.taskId(), target.pathNodeId(), target.spaceId(), mode));
        bundle.setGenerationTrigger(normalizeText(dto.getGenerationTrigger(), "manual"));
        bundle.setGenerationReason(normalizeText(dto.getGenerationReason(), "用户提前生成学习脚本"));
        bundle.setExpiresAt(LocalDateTime.now().plusDays(DEFAULT_EXPIRES_DAYS));
        bundle.setSourceContextHash(sourceContextHash(context));
        bundle.setGenerationContext(toJson(new LinkedHashMap<>(Map.of(
                "source", resolveScriptSource(session),
                "taskId", target.taskId(),
                "pathNodeId", target.pathNodeId() == null ? "" : target.pathNodeId(),
                "spaceId", target.spaceId() == null ? "" : target.spaceId(),
                "mode", mode
        ))));
        bundle.setTargetKnowledgePoints(target.pathNodeId() == null ? null : toJson(Map.of(
                "pathNodeId", target.pathNodeId(),
                "title", normalizeText(context.getPathNodeTitle(), "")
        )));
        bundle.setBundleJson(toJson(session));
        bundle.setIsDeleted(0);
        lessonBundleMapper.insert(bundle);

        log.info("[LessonBundle] generated: userId={}, taskId={}, pathNodeId={}, bundleId={}, trigger={}",
                userId, target.taskId(), target.pathNodeId(), bundleId, bundle.getGenerationTrigger());
        return toStatusVO(bundle);
    }

    @Override
    public LessonBundleStatusVO getBundleStatus(Long userId, String taskId, Long pathNodeId, String spaceId, String mode) {
        String normalizedMode = normalizeMode(mode);
        BundleTarget target = resolveStatusTarget(userId, taskId, pathNodeId, spaceId);
        LessonBundle bundle = selectLatestBundle(userId, target.taskId(), target.pathNodeId(), target.spaceId(), normalizedMode);
        if (bundle == null) {
            LessonBundleStatusVO vo = new LessonBundleStatusVO();
            vo.setTaskId(target.taskId());
            vo.setPathNodeId(target.pathNodeId());
            vo.setSpaceId(target.spaceId());
            vo.setMode(normalizedMode);
            vo.setStatus("not_generated");
            return vo;
        }
        if ("ready".equals(bundle.getStatus()) && bundle.getExpiresAt() != null
                && bundle.getExpiresAt().isBefore(LocalDateTime.now())) {
            LessonBundleStatusVO vo = toStatusVO(bundle);
            vo.setStatus("expired");
            return vo;
        }
        return toStatusVO(bundle);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void preheatNextPathNode(Long userId, Long currentPathNodeId, String mode) {
        if (currentPathNodeId == null) {
            return;
        }
        LearningPathNode current = learningPathNodeMapper.selectById(currentPathNodeId);
        if (current == null || isDeleted(current.getIsDeleted())) {
            return;
        }
        LearningSpace space = learningSpaceMapper.selectById(current.getSpaceId());
        if (space == null || !userId.equals(space.getUserId()) || isDeleted(space.getIsDeleted())) {
            return;
        }

        LearningPathNode next = findNextNode(current);
        if (next == null) {
            return;
        }

        LessonBundleGenerateDTO dto = new LessonBundleGenerateDTO();
        dto.setPathNodeId(next.getId());
        dto.setSpaceId(String.valueOf(next.getSpaceId()));
        dto.setMode(normalizeMode(mode));
        dto.setForceRegenerate(false);
        dto.setGenerationTrigger("after_complete");
        dto.setGenerationReason("完成上一个知识点后自动预热下一个学习脚本");
        generateBundle(userId, dto);
    }

    private BundleTarget resolveTarget(Long userId, LessonBundleGenerateDTO dto) {
        if (dto == null) {
            throw new BusinessException("生成参数不能为空");
        }
        return resolveStatusTarget(userId, dto.getTaskId(), dto.getPathNodeId(), dto.getSpaceId());
    }

    private BundleTarget resolveStatusTarget(Long userId, String taskId, Long pathNodeId, String spaceId) {
        String normalizedTaskId = normalizeText(taskId, "");
        if (isNumeric(normalizedTaskId)) {
            Task task = taskMapper.selectById(Long.valueOf(normalizedTaskId));
            if (task == null || !userId.equals(task.getUserId()) || isDeleted(task.getIsDeleted())) {
                throw new BusinessException("任务不存在或无权访问");
            }
            return new BundleTarget(String.valueOf(task.getId()), task.getPathNodeId(), stringId(task.getSpaceId()));
        }

        if (pathNodeId != null) {
            LearningPathNode node = learningPathNodeMapper.selectById(pathNodeId);
            if (node == null || isDeleted(node.getIsDeleted())) {
                throw new BusinessException("学习路径节点不存在");
            }
            LearningSpace space = learningSpaceMapper.selectById(node.getSpaceId());
            if (space == null || !userId.equals(space.getUserId()) || isDeleted(space.getIsDeleted())) {
                throw new BusinessException("无权访问该学习路径节点");
            }
            return new BundleTarget(normalizedTaskId.isBlank() ? "path-node-" + node.getId() : normalizedTaskId,
                    node.getId(), String.valueOf(node.getSpaceId()));
        }

        if (!normalizedTaskId.isBlank()) {
            return new BundleTarget(normalizedTaskId, null, normalizeText(spaceId, null));
        }
        throw new BusinessException("请提供 taskId 或 pathNodeId");
    }

    private LessonScriptContext buildScriptContext(Long userId, BundleTarget target, String mode) {
        LessonScriptContext context = new LessonScriptContext(userId, target.taskId(), target.spaceId(), mode, null);
        if (isNumeric(target.taskId())) {
            Task task = taskMapper.selectById(Long.valueOf(target.taskId()));
            if (task != null && userId.equals(task.getUserId()) && !isDeleted(task.getIsDeleted())) {
                context.setTaskTitle(task.getTitle());
                context.setTaskDescription(task.getDescription());
                context.setTaskType(task.getTaskType());
                context.setEstimatedDurationMinutes(task.getEstimatedDurationMinutes());
                if (task.getSpaceId() != null) {
                    context.setSpaceId(String.valueOf(task.getSpaceId()));
                    fillSpaceContext(context, task.getSpaceId(), userId);
                }
                if (task.getPathNodeId() != null) {
                    fillPathNodeContext(context, task.getPathNodeId());
                }
            }
            return context;
        }
        if (isNumeric(target.spaceId())) {
            fillSpaceContext(context, Long.valueOf(target.spaceId()), userId);
        }
        if (target.pathNodeId() != null) {
            fillPathNodeContext(context, target.pathNodeId());
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

    private LearningPathNode findNextNode(LearningPathNode current) {
        LearningPathEdge edge = learningPathEdgeMapper.selectOne(new QueryWrapper<LearningPathEdge>()
                .eq("space_id", current.getSpaceId())
                .eq("from_node_id", current.getId())
                .eq("edge_type", "prerequisite_of")
                .eq("is_deleted", 0)
                .orderByAsc("id")
                .last("LIMIT 1"));
        if (edge != null && edge.getToNodeId() != null) {
            LearningPathNode edgeNext = learningPathNodeMapper.selectById(edge.getToNodeId());
            if (edgeNext != null && !current.getId().equals(edgeNext.getId()) && !isDeleted(edgeNext.getIsDeleted())) {
                return edgeNext;
            }
        }

        return learningPathNodeMapper.selectOne(new QueryWrapper<LearningPathNode>()
                .eq("space_id", current.getSpaceId())
                .eq("is_deleted", 0)
                .ne("id", current.getId())
                .gt(current.getSortOrder() != null, "sort_order", current.getSortOrder())
                .gt(current.getSortOrder() == null, "id", current.getId())
                .ne("node_type", "stage")
                .orderByAsc("sort_order")
                .orderByAsc("id")
                .last("LIMIT 1"));
    }

    private LessonBundle selectLatestBundle(Long userId, String taskId, Long pathNodeId, String spaceId, String mode) {
        QueryWrapper<LessonBundle> query = new QueryWrapper<LessonBundle>()
                .eq("user_id", userId)
                .eq("mode", mode)
                .eq("is_deleted", 0)
                .orderByDesc("create_time")
                .last("LIMIT 1");
        if (pathNodeId != null) {
            query.eq("path_node_id", pathNodeId);
        } else {
            query.eq("task_id", taskId);
        }
        if (spaceId != null && !spaceId.isBlank()) {
            query.eq("space_id", spaceId);
        }
        return lessonBundleMapper.selectOne(query);
    }

    private int resolveNextVersion(Long userId, String taskId, Long pathNodeId, String spaceId, String mode) {
        LessonBundle latest = selectLatestBundle(userId, taskId, pathNodeId, spaceId, mode);
        if (latest == null || latest.getVersion() == null) {
            return 1;
        }
        return latest.getVersion() + 1;
    }

    private boolean isReusable(LessonBundle bundle) {
        if (bundle == null || !"ready".equals(bundle.getStatus())) {
            return false;
        }
        return bundle.getExpiresAt() == null || bundle.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private LessonBundleStatusVO toStatusVO(LessonBundle bundle) {
        LessonBundleStatusVO vo = new LessonBundleStatusVO();
        vo.setBundleId(bundle.getBundleId());
        vo.setTaskId(bundle.getTaskId());
        vo.setPathNodeId(bundle.getPathNodeId());
        vo.setSpaceId(bundle.getSpaceId());
        vo.setMode(bundle.getMode());
        vo.setStatus(bundle.getStatus());
        vo.setVersion(bundle.getVersion());
        vo.setGenerationTrigger(bundle.getGenerationTrigger());
        vo.setGenerationReason(bundle.getGenerationReason());
        vo.setExpiresAt(bundle.getExpiresAt());
        vo.setCreateTime(bundle.getCreateTime());
        vo.setUpdateTime(bundle.getUpdateTime());
        vo.setErrorMessage(bundle.getErrorMessage());
        return vo;
    }

    private String sourceContextHash(LessonScriptContext context) {
        return Integer.toHexString(Objects.hash(
                context.getTaskId(),
                context.getSpaceId(),
                context.getMode(),
                context.getTaskTitle(),
                context.getPathNodeTitle(),
                context.getPathNodeDescription(),
                context.getEstimatedDurationMinutes()
        ));
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

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("学习脚本序列化失败");
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "learn";
        }
        return mode;
    }

    private String normalizeText(String value, String fallback) {
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

    private record BundleTarget(String taskId, Long pathNodeId, String spaceId) {
    }
}
