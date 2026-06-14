package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.mapper.TaskMapper;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
}
