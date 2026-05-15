package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.entity.Task;
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

    @GetMapping
    @Operation(summary = "获取学习空间的任务列表", description = "返回指定学习空间的所有任务")
    public Result<List<Task>> listBySpace(@RequestParam Long spaceId) {
        return Result.success(taskService.getBySpaceId(spaceId));
    }

    @GetMapping("/today")
    @Operation(summary = "获取今日任务", description = "返回当前用户今日所有学习任务")
    public Result<List<Task>> today() {
        Long userId = getCurrentUserId();
        return Result.success(taskService.getTodayTasks(userId));
    }

    @GetMapping("/pending")
    @Operation(summary = "获取待完成任务", description = "返回当前用户所有待完成的学习任务")
    public Result<List<Task>> pending() {
        Long userId = getCurrentUserId();
        return Result.success(taskService.getPendingTasks(userId));
    }

    @GetMapping("/overdue")
    @Operation(summary = "获取延期任务", description = "返回当前用户所有已延期的学习任务")
    public Result<List<Task>> overdue() {
        Long userId = getCurrentUserId();
        return Result.success(taskService.getOverdueTasks(userId));
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
