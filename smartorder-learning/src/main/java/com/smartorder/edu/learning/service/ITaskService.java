package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Task;

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
}
