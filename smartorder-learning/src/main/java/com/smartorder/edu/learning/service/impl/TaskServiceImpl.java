package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.mapper.TaskMapper;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements ITaskService {

    private final TaskMapper taskMapper;

    @Override
    public List<Task> batchInsert(List<Task> tasks) {
        for (Task task : tasks) {
            if (task.getStatus() == null) task.setStatus("pending");
            if (task.getPriority() == null) task.setPriority(3);
            if (task.getIsDeleted() == null) task.setIsDeleted(0);
            taskMapper.insert(task);
        }
        return tasks;
    }

    @Override
    public List<Task> getBySpaceId(Long spaceId) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("is_deleted", 0).orderByAsc("sort_order");
        return taskMapper.selectList(qw);
    }
}
