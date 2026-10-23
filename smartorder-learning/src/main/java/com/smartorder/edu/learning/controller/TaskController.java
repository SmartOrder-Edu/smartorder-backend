package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.TaskVO;
import com.smartorder.edu.learning.dto.CompleteTaskDTO;
import com.smartorder.edu.learning.dto.CreateTaskDTO;
import com.smartorder.edu.learning.dto.PlanTodayTasksDTO;
import com.smartorder.edu.learning.dto.UpdateTaskStatusDTO;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.ITaskService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务控制器
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/tasks")
@Tag(name = "任务管理", description = "学习任务的查询接口")
public class TaskController {

    private final ITaskService taskService;
    private final ILearningSpaceService learningSpaceService;

    @GetMapping
    @Operation(summary = "获取学习空间的任务列表", description = "返回指定学习空间的所有任务")
    public Result<List<TaskVO>> listBySpace(@RequestParam Long spaceId) {
        Long userId = getCurrentUserId();
        return Result.success(toVOList(taskService.getBySpaceId(spaceId).stream()
                .filter(task -> userId.equals(task.getUserId()))
                .toList(), userId));
    }

    @GetMapping("/today")
    @Operation(summary = "获取今日任务", description = "返回当前用户今日所有学习任务")
    public Result<List<TaskVO>> today() {
        Long userId = getCurrentUserId();
        return Result.success(toVOList(taskService.getTodayTasks(userId), userId));
    }

    @GetMapping("/pending")
    @Operation(summary = "获取待完成任务", description = "返回当前用户所有待完成的学习任务")
    public Result<List<TaskVO>> pending() {
        Long userId = getCurrentUserId();
        return Result.success(toVOList(taskService.getPendingTasks(userId), userId));
    }

    @GetMapping("/overdue")
    @Operation(summary = "获取延期任务", description = "返回当前用户所有已延期的学习任务")
    public Result<List<TaskVO>> overdue() {
        Long userId = getCurrentUserId();
        return Result.success(toVOList(taskService.getOverdueTasks(userId), userId));
    }

    @PostMapping
    @Operation(summary = "创建学习任务", description = "创建一个可执行学习任务，可绑定学习脚本、资源或练习测评")
    public Result<TaskVO> create(@RequestBody CreateTaskDTO dto) {
        Long userId = getCurrentUserId();
        return Result.success(toVO(taskService.createTask(userId, dto)));
    }

    @PostMapping("/plan-today")
    @Operation(summary = "生成今日学习任务", description = "根据当前用户活跃学习空间和学习路径生成今日可执行任务")
    public Result<List<TaskVO>> planToday(@RequestBody(required = false) PlanTodayTasksDTO dto) {
        Long userId = getCurrentUserId();
        return Result.success(toVOList(taskService.planTodayTasks(userId, dto), userId));
    }

    @PatchMapping("/{taskId}/status")
    @Operation(summary = "更新任务状态", description = "更新任务状态，如 pending/in_progress/completed/cancelled")
    public Result<TaskVO> updateStatus(@PathVariable Long taskId, @RequestBody UpdateTaskStatusDTO dto) {
        Long userId = getCurrentUserId();
        return Result.success(toVO(taskService.updateStatus(taskId, userId, dto.getStatus())));
    }

    @PatchMapping("/{taskId}/complete")
    @Operation(summary = "完成任务", description = "完成任务并记录实际耗时")
    public Result<TaskVO> complete(@PathVariable Long taskId, @RequestBody(required = false) CompleteTaskDTO dto) {
        Long userId = getCurrentUserId();
        Integer actualDurationMinutes = dto == null ? null : dto.getActualDurationMinutes();
        return Result.success(toVO(taskService.completeTask(taskId, userId, actualDurationMinutes)));
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }

    private TaskVO toVO(Task task) {
        java.util.Map<Long, String> spaceMap = new java.util.HashMap<>();
        if (task.getSpaceId() != null) {
            try {
                LearningSpaceVO space = learningSpaceService.getSpaceById(task.getUserId(), task.getSpaceId());
                if (space != null) {
                    spaceMap.put(space.getId(), space.getName());
                }
            } catch (Exception e) {
                log.error("获取学习空间名称失败, spaceId: {}", task.getSpaceId(), e);
            }
        }
        return toVO(task, spaceMap);
    }

    private TaskVO toVO(Task task, java.util.Map<Long, String> spaceMap) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setSpaceId(task.getSpaceId());
        vo.setPathNodeId(task.getPathNodeId());
        vo.setTitle(task.getTitle());
        vo.setDescription(task.getDescription());
        vo.setTaskType(task.getTaskType());
        vo.setActionType(task.getActionType());
        vo.setTargetType(task.getTargetType());
        vo.setTargetId(task.getTargetId());
        vo.setPriority(task.getPriority());
        vo.setStatus(task.getStatus());
        vo.setDueDate(task.getDueDate());
        vo.setDueTime(task.getDueTime());
        vo.setEstimatedDurationMinutes(task.getEstimatedDurationMinutes());
        vo.setActualDurationMinutes(task.getActualDurationMinutes());
        vo.setCompletedAt(task.getCompletedAt());
        vo.setAiSuggestion(task.getAiSuggestion());
        vo.setAgentReason(task.getAgentReason());
        vo.setMetadata(task.getMetadata());
        vo.setIsToday(task.getDueDate() != null && task.getDueDate().equals(java.time.LocalDate.now()));
        vo.setIsOverdue(task.getDueDate() != null && task.getDueDate().isBefore(java.time.LocalDate.now())
                && !"completed".equals(task.getStatus()) && !"cancelled".equals(task.getStatus()));
        if (task.getSpaceId() != null && spaceMap != null) {
            vo.setSpaceName(spaceMap.get(task.getSpaceId()));
        }
        return vo;
    }

    private List<TaskVO> toVOList(List<Task> tasks, Long userId) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, String> spaceMap = new java.util.HashMap<>();
        try {
            List<LearningSpaceVO> spaces = learningSpaceService.listSpaces(userId, null);
            if (spaces != null) {
                for (LearningSpaceVO space : spaces) {
                    spaceMap.put(space.getId(), space.getName());
                }
            }
        } catch (Exception e) {
            log.error("加载用户学习空间列表失败, userId: {}", userId, e);
        }
        return tasks.stream().map(task -> toVO(task, spaceMap)).toList();
    }
}
