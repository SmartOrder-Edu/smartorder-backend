package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.dto.CreateTaskDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * 任务服务接口
 */
public interface ITaskService {

    /**
     * 批量插入任务
     */
    List<Task> batchInsert(List<Task> tasks);

    /**
     * 创建单个学习任务
     */
    Task createTask(Long userId, CreateTaskDTO dto);

    /**
     * 根据当前用户活跃学习空间生成今日可执行任务。
     */
    List<Task> planTodayTasks(Long userId, Integer maxTasks);

    /**
     * 根据学习空间ID查询任务列表
     */
    List<Task> getBySpaceId(Long spaceId);

    /**
     * 查询用户今日任务（跨空间）
     */
    List<Task> getTodayTasks(Long userId);

    /**
     * 查询用户指定日期的任务
     */
    List<Task> getTasksByDate(Long userId, LocalDate date);

    /**
     * 查询用户所有待完成任务
     */
    List<Task> getPendingTasks(Long userId);

    /**
     * 查询用户延期任务
     */
    List<Task> getOverdueTasks(Long userId);

    /**
     * 查询指定学习空间指定日期的任务
     */
    List<Task> getBySpaceIdAndDate(Long spaceId, Long userId, LocalDate date);

    /**
     * 查询指定学习空间的待完成任务
     */
    List<Task> getPendingBySpaceId(Long spaceId, Long userId);

    /**
     * 查询指定学习空间的逾期任务
     */
    List<Task> getOverdueBySpaceId(Long spaceId, Long userId);

    /**
     * 提交/完成指定的任务
     */
    void completeTask(Long taskId, Long userId);

    /**
     * 更新任务状态
     */
    Task updateStatus(Long taskId, Long userId, String status);

    /**
     * 完成任务并返回更新后的任务
     */
    Task completeTask(Long taskId, Long userId, Integer actualDurationMinutes);
}
