package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Task;

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
}
