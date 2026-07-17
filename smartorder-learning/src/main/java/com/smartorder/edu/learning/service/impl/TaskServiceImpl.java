package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.dto.CreateTaskDTO;
import com.smartorder.edu.learning.dto.PlanTodayTasksDTO;
import com.smartorder.edu.learning.mapper.LearningPathNodeMapper;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.mapper.TaskMapper;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
    private static final Set<String> ALLOWED_PLAN_STRATEGIES = Set.of(
            "deadline_first", "weakness_first", "balanced"
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
    public List<Task> planTodayTasks(Long userId, Integer maxTasks) {
        PlanTodayTasksDTO dto = new PlanTodayTasksDTO();
        dto.setMaxTasks(maxTasks);
        return planTodayTasks(userId, dto);
    }

    @Override
    public List<Task> planTodayTasks(Long userId, PlanTodayTasksDTO dto) {
        PlanTodayTasksDTO request = dto == null ? new PlanTodayTasksDTO() : dto;
        int limit = normalizePlanLimit(request.getMaxTasks());
        int remainingMinutes = normalizeDailyBudget(request.getTotalAvailableMinutes(), limit);
        String strategy = normalizeStrategy(request.getStrategy());
        Set<String> allowedActionTypes = normalizeAllowedActionTypes(request.getAllowedActionTypes());
        if (!allowedActionTypes.contains("learn_script")) {
            log.info("今日任务规划跳过：allowedActionTypes 未包含 learn_script，userId={}", userId);
            return List.of();
        }

        List<LearningSpace> spaces = sortSpacesByStrategy(selectActiveSpaces(userId), strategy);
        List<Task> createdTasks = new ArrayList<>();

        for (LearningSpace space : spaces) {
            if (createdTasks.size() >= limit) {
                break;
            }
            if (remainingMinutes < 5) {
                break;
            }
            if (hasActiveTodayTask(space.getId(), userId)) {
                continue;
            }

            LearningPathNode nextNode = selectNextPathNode(space.getId());
            if (nextNode == null) {
                continue;
            }

            Task task = buildTodayLearningTask(userId, space, nextNode, createdTasks.size(), strategy);
            int plannedMinutes = Math.min(task.getEstimatedDurationMinutes(), remainingMinutes);
            task.setEstimatedDurationMinutes(Math.max(5, plannedMinutes));
            taskMapper.insert(task);
            createdTasks.add(task);
            remainingMinutes -= task.getEstimatedDurationMinutes();
        }

        return createdTasks;
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
                .in("status", "pending", "in_progress", "doing").orderByAsc("priority").orderByAsc("due_date");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getOverdueTasks(Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("is_deleted", 0).lt("due_date", LocalDate.now())
                .in("status", "pending", "in_progress", "doing").orderByAsc("due_date");
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
                .in("status", "pending", "in_progress", "doing").orderByAsc("priority").orderByAsc("due_date");
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> getOverdueBySpaceId(Long spaceId, Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .lt("due_date", LocalDate.now()).in("status", "pending", "in_progress", "doing").orderByAsc("due_date");
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

    private List<LearningSpace> selectActiveSpaces(Long userId) {
        QueryWrapper<LearningSpace> qw = new QueryWrapper<>();
        qw.eq("user_id", userId)
                .eq("status", "active")
                .eq("is_deleted", 0)
                .orderByAsc("sort_order")
                .orderByDesc("create_time");
        return learningSpaceMapper.selectList(qw);
    }

    private boolean hasActiveTodayTask(Long spaceId, Long userId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId)
                .eq("user_id", userId)
                .eq("is_deleted", 0)
                .eq("due_date", LocalDate.now())
                .in("status", "pending", "in_progress", "doing");
        return taskMapper.selectCount(qw) > 0;
    }

    private LearningPathNode selectNextPathNode(Long spaceId) {
        QueryWrapper<LearningPathNode> nodeQuery = new QueryWrapper<>();
        nodeQuery.eq("space_id", spaceId)
                .eq("is_deleted", 0)
                .orderByAsc("sort_order")
                .orderByAsc("id");
        List<LearningPathNode> nodes = learningPathNodeMapper.selectList(nodeQuery);
        if (nodes.isEmpty()) {
            return null;
        }

        Set<Long> assignedNodeIds = selectAssignedPathNodeIds(spaceId);
        return nodes.stream()
                .filter(node -> node.getTitle() != null && !node.getTitle().isBlank())
                .filter(node -> node.getId() != null && !assignedNodeIds.contains(node.getId()))
                .sorted(Comparator
                        .comparing((LearningPathNode node) -> "stage".equals(node.getNodeType()) ? 1 : 0)
                        .thenComparing(node -> node.getSortOrder() == null ? Integer.MAX_VALUE : node.getSortOrder())
                        .thenComparing(node -> node.getId() == null ? Long.MAX_VALUE : node.getId()))
                .findFirst()
                .orElse(null);
    }

    private Set<Long> selectAssignedPathNodeIds(Long spaceId) {
        QueryWrapper<Task> taskQuery = new QueryWrapper<>();
        taskQuery.eq("space_id", spaceId)
                .eq("is_deleted", 0)
                .isNotNull("path_node_id")
                .ne("status", "cancelled");
        List<Task> tasks = taskMapper.selectList(taskQuery);
        Set<Long> assignedNodeIds = new HashSet<>();
        for (Task task : tasks) {
            if (task.getPathNodeId() != null) {
                assignedNodeIds.add(task.getPathNodeId());
            }
        }
        return assignedNodeIds;
    }

    private Task buildTodayLearningTask(Long userId, LearningSpace space, LearningPathNode node, int sortOrder, String strategy) {
        Task task = new Task();
        task.setUserId(userId);
        task.setSpaceId(space.getId());
        task.setPathNodeId(node.getId());
        task.setTitle("学习：" + node.getTitle().trim());
        task.setDescription(firstNonBlank(node.getDescription(), "跟随 Agent 学习该路径节点的核心内容。"));
        task.setTaskType("daily");
        task.setActionType("learn_script");
        task.setTargetType("path_node");
        task.setTargetId(node.getId());
        task.setPriority(calculateSpacePriority(space));
        task.setStatus("pending");
        task.setDueDate(LocalDate.now());
        task.setDueTime(defaultDueTime(sortOrder));
        task.setEstimatedDurationMinutes(normalizeEstimatedMinutes(node.getEstimatedDurationMinutes()));
        task.setSortOrder(sortOrder);
        task.setAiSuggestion("建议先完成本节点学习脚本，再根据掌握情况生成练习或复习任务。");
        task.setAgentReason(buildPlanReason(space, strategy));
        task.setMetadata("{\"source\":\"plan_today\",\"target\":\"path_node\",\"strategy\":\"" + strategy + "\"}");
        task.setIsDeleted(0);
        return task;
    }

    private String buildPlanReason(LearningSpace space, String strategy) {
        String base = "系统根据活跃学习空间「" + space.getName() + "」的学习路径，选择当前尚未安排的下一节点。";
        if ("deadline_first".equals(strategy)) {
            return base + "当前采用截止时间优先策略。";
        }
        if ("weakness_first".equals(strategy)) {
            return base + "当前采用薄弱优先策略，优先推进进度较低的学习空间。";
        }
        return base + "当前采用均衡分配策略，尽量保持多学习空间稳定推进。";
    }

    private Integer calculateSpacePriority(LearningSpace space) {
        LocalDate targetEndDate = space.getTargetEndDate();
        if (targetEndDate == null) {
            return 3;
        }
        LocalDate today = LocalDate.now();
        if (!targetEndDate.isBefore(today) && !targetEndDate.isAfter(today.plusDays(3))) {
            return 1;
        }
        if (targetEndDate.isBefore(today)) {
            return 2;
        }
        return 3;
    }

    private LocalTime defaultDueTime(int sortOrder) {
        int hour = Math.min(21, 9 + Math.max(0, sortOrder) * 2);
        return LocalTime.of(hour, 0);
    }

    private Integer normalizeEstimatedMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return 25;
        }
        return Math.max(5, Math.min(120, minutes));
    }

    private int normalizePlanLimit(Integer maxTasks) {
        if (maxTasks == null) {
            return 3;
        }
        return Math.max(1, Math.min(8, maxTasks));
    }

    private int normalizeDailyBudget(Integer totalAvailableMinutes, int limit) {
        if (totalAvailableMinutes == null || totalAvailableMinutes <= 0) {
            return Math.max(30, limit * 30);
        }
        return Math.max(5, Math.min(600, totalAvailableMinutes));
    }

    private String normalizeStrategy(String strategy) {
        String normalized = normalizeText(strategy);
        if (normalized == null) {
            return "deadline_first";
        }
        if (!ALLOWED_PLAN_STRATEGIES.contains(normalized)) {
            throw new BusinessException("不支持的任务编排策略：" + normalized);
        }
        return normalized;
    }

    private Set<String> normalizeAllowedActionTypes(List<String> allowedActionTypes) {
        if (allowedActionTypes == null || allowedActionTypes.isEmpty()) {
            return ALLOWED_ACTION_TYPES;
        }
        Set<String> normalized = new HashSet<>();
        for (String value : allowedActionTypes) {
            String actionType = normalizeText(value);
            if (actionType == null) {
                continue;
            }
            if (!ALLOWED_ACTION_TYPES.contains(actionType)) {
                throw new BusinessException("不支持的执行动作类型：" + actionType);
            }
            normalized.add(actionType);
        }
        return normalized.isEmpty() ? ALLOWED_ACTION_TYPES : normalized;
    }

    private List<LearningSpace> sortSpacesByStrategy(List<LearningSpace> spaces, String strategy) {
        List<LearningSpace> sorted = new ArrayList<>(spaces);
        if ("deadline_first".equals(strategy)) {
            sorted.sort(Comparator
                    .comparing((LearningSpace space) -> space.getTargetEndDate() == null ? LocalDate.MAX : space.getTargetEndDate())
                    .thenComparing(space -> space.getSortOrder() == null ? Integer.MAX_VALUE : space.getSortOrder())
                    .thenComparing(space -> space.getCreateTime() == null ? LocalDateTime.MIN : space.getCreateTime(), Comparator.reverseOrder()));
            return sorted;
        }
        if ("weakness_first".equals(strategy)) {
            sorted.sort(Comparator
                    .comparing((LearningSpace space) -> space.getProgress() == null ? 100.0 : space.getProgress())
                    .thenComparing(space -> space.getTargetEndDate() == null ? LocalDate.MAX : space.getTargetEndDate()));
        }
        return sorted;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
