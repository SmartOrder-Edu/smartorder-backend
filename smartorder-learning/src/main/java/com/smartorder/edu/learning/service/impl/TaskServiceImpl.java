package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.dto.CreateTaskDTO;
import com.smartorder.edu.learning.mapper.LearningPathNodeMapper;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.mapper.TaskMapper;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements ITaskService {

    private static final Set<String> ALLOWED_TASK_TYPES = Set.of(
            "daily", "milestone", "exam", "review", "remedial", "project"
    );
    private static final Set<String> ALLOWED_ACTION_TYPES = Set.of(
            "learn_script", "read_resource", "watch_video", "practice_set",
            "assessment", "mistake_review", "project_practice", "summary_reflection"
    );
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "pending", "in_progress", "doing", "completed", "overdue", "cancelled"
    );

    private final TaskMapper taskMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final LearningPathNodeMapper learningPathNodeMapper;

    @Override
    public List<Task> batchInsert(List<Task> tasks) {
        for (Task task : tasks) {
            applyDefaults(task);
            taskMapper.insert(task);
        }
        return tasks;
    }

    @Override
    public Task createTask(Long userId, CreateTaskDTO dto) {
        if (dto == null) {
            throw new BusinessException("任务参数不能为空");
        }
        if (dto.getSpaceId() == null) {
            throw new BusinessException("学习空间不能为空");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BusinessException("任务标题不能为空");
        }
        validateSpaceOwnership(userId, dto.getSpaceId());
        validatePathNode(dto.getSpaceId(), dto.getPathNodeId());

        Task task = new Task();
        task.setUserId(userId);
        task.setSpaceId(dto.getSpaceId());
        task.setPathNodeId(dto.getPathNodeId());
        task.setTitle(dto.getTitle().trim());
        task.setDescription(dto.getDescription());
        task.setTaskType(normalizeTaskType(dto.getTaskType()));
        task.setActionType(normalizeActionType(dto.getActionType()));
        task.setTargetType(normalizeText(dto.getTargetType()));
        task.setTargetId(dto.getTargetId());
        task.setPriority(normalizePriority(dto.getPriority()));
        task.setStatus(normalizeStatus(dto.getStatus(), "pending"));
        task.setDueDate(dto.getDueDate());
        task.setDueTime(dto.getDueTime());
        task.setEstimatedDurationMinutes(dto.getEstimatedDurationMinutes());
        task.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        task.setAiSuggestion(dto.getAiSuggestion());
        task.setAgentReason(dto.getAgentReason());
        task.setMetadata(dto.getMetadata());
        task.setIsDeleted(0);
        taskMapper.insert(task);
        return task;
    }

    @Override
    public List<Task> getBySpaceId(Long spaceId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("is_deleted", 0).orderByAsc("sort_order");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getTodayTasks(Long userId) {
        return getTasksByDate(userId, LocalDate.now());
    }

    @Override
    public List<Task> getTasksByDate(Long userId, LocalDate date) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("is_deleted", 0).eq("due_date", date).orderByAsc("priority").orderByAsc("sort_order");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getPendingTasks(Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("is_deleted", 0)
                .in("status", "pending", "doing").orderByAsc("priority").orderByAsc("due_date");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getOverdueTasks(Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("is_deleted", 0).lt("due_date", LocalDate.now())
                .in("status", "pending", "doing").orderByAsc("due_date");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getBySpaceIdAndDate(Long spaceId, Long userId, LocalDate date) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .eq("due_date", date).orderByAsc("priority").orderByAsc("sort_order");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getPendingBySpaceId(Long spaceId, Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .in("status", "pending", "doing").orderByAsc("priority").orderByAsc("due_date");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getOverdueBySpaceId(Long spaceId, Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .lt("due_date", LocalDate.now()).in("status", "pending", "doing").orderByAsc("due_date");
        return taskMapper.selectList(qw);
    }

    @Override
    public void completeTask(Long taskId, Long userId) {
        completeTask(taskId, userId, null);
    }

    @Override
    public Task updateStatus(Long taskId, Long userId, String status) {
        Task task = requireOwnedTask(taskId, userId);
        String normalizedStatus = normalizeStatus(status, null);
        task.setStatus(normalizedStatus);
        if ("completed".equals(normalizedStatus) && task.getCompletedAt() == null) {
            task.setCompletedAt(LocalDateTime.now());
        }
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Override
    public Task completeTask(Long taskId, Long userId, Integer actualDurationMinutes) {
        Task task = requireOwnedTask(taskId, userId);
        task.setStatus("completed");
        if (actualDurationMinutes != null && actualDurationMinutes >= 0) {
            task.setActualDurationMinutes(actualDurationMinutes);
        }
        task.setCompletedAt(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    private Task requireOwnedTask(Long taskId, Long userId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null || (task.getIsDeleted() != null && task.getIsDeleted() == 1)) {
            throw new BusinessException("任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此任务");
        }
        return task;
    }

    private void applyDefaults(Task task) {
        if (task.getTaskType() == null) task.setTaskType("daily");
        if (task.getActionType() == null) task.setActionType("learn_script");
        if (task.getStatus() == null) task.setStatus("pending");
        if (task.getPriority() == null) task.setPriority(3);
        if (task.getSortOrder() == null) task.setSortOrder(0);
        if (task.getIsDeleted() == null) task.setIsDeleted(0);
    }

    private void validateSpaceOwnership(Long userId, Long spaceId) {
        LearningSpace space = learningSpaceMapper.selectById(spaceId);
        if (space == null || (space.getIsDeleted() != null && space.getIsDeleted() == 1)) {
            throw new BusinessException("学习空间不存在");
        }
        if (!userId.equals(space.getUserId())) {
            throw new BusinessException("无权访问该学习空间");
        }
    }

    private void validatePathNode(Long spaceId, Long pathNodeId) {
        if (pathNodeId == null) {
            return;
        }
        LearningPathNode node = learningPathNodeMapper.selectById(pathNodeId);
        if (node == null || (node.getIsDeleted() != null && node.getIsDeleted() == 1)) {
            throw new BusinessException("学习路径节点不存在");
        }
        if (!spaceId.equals(node.getSpaceId())) {
            throw new BusinessException("学习路径节点不属于该学习空间");
        }
    }

    private String normalizeTaskType(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return "daily";
        }
        if (!ALLOWED_TASK_TYPES.contains(normalized)) {
            throw new BusinessException("不支持的计划类型：" + normalized);
        }
        return normalized;
    }

    private String normalizeActionType(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return "learn_script";
        }
        if (!ALLOWED_ACTION_TYPES.contains(normalized)) {
            throw new BusinessException("不支持的执行动作类型：" + normalized);
        }
        return normalized;
    }

    private String normalizeStatus(String value, String fallback) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new BusinessException("任务状态不能为空");
        }
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new BusinessException("不支持的任务状态：" + normalized);
        }
        return normalized;
    }

    private Integer normalizePriority(Integer priority) {
        if (priority == null) {
            return 3;
        }
        if (priority < 1) {
            return 1;
        }
        if (priority > 5) {
            return 5;
        }
        return priority;
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
