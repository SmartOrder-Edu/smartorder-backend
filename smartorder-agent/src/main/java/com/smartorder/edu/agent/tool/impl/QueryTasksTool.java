package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询任务工具——查询用户今日/延期/待完成任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryTasksTool implements ToolHandler {

    private final ITaskService taskService;

    @Override
    public String name() {
        return "queryTasks";
    }

    @Override
    public String description() {
        return "查询用户的学习任务列表，可以按空间ID、日期筛选。支持查询今日任务、待完成任务、延期任务。用于回答今天要学什么、有什么待办任务等问题";
    }

    @Override
    public Class<?> requestType() {
        return QueryTasksRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.TASK;
    }

    @Override
    public ToolCategory.RiskLevel riskLevel() {
        return ToolCategory.RiskLevel.READ;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        QueryTasksRequest req = (QueryTasksRequest) request;
        Long userId = context.getUserId();

        log.info("[QueryTasks] userId={}, spaceId={}, scope={}", userId, req.getSpaceId(), req.getScope());

        String scope = req.getScope() != null ? req.getScope() : "today";
        List<Task> tasks = switch (scope) {
            case "today" -> taskService.getTodayTasks(userId);
            case "pending" -> taskService.getPendingTasks(userId);
            case "overdue" -> taskService.getOverdueTasks(userId);
            default -> taskService.getTodayTasks(userId);
        };

        // 如果指定了 spaceId，则过滤
        if (req.getSpaceId() != null) {
            tasks = tasks.stream().filter(t -> req.getSpaceId().equals(t.getSpaceId())).toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("taskCount", tasks.size());
        result.put("tasks", tasks.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("spaceId", t.getSpaceId());
            m.put("taskType", t.getTaskType());
            m.put("priority", t.getPriority());
            m.put("status", t.getStatus());
            m.put("dueDate", t.getDueDate() != null ? t.getDueDate().toString() : null);
            m.put("estimatedDurationMinutes", t.getEstimatedDurationMinutes());
            m.put("aiSuggestion", t.getAiSuggestion());
            return m;
        }).toList());

        return result;
    }

    @Data
    public static class QueryTasksRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间ID，可选，不传则查询所有空间")
        private Long spaceId;
        @dev.langchain4j.model.output.structured.Description("查询范围：today/pending/overdue，默认today")
        private String scope;
    }
}
