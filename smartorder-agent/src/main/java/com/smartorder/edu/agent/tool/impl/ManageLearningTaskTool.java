package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 管理学习任务工具——迁移自 LearningPlanCreationTool
 *
 * 第一阶段保持原有创建逻辑，后续阶段重构为规范的 ToolHandler
 * 当前委托给原有的 LearningPlanCreationTool 执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManageLearningTaskTool implements ToolHandler {

    private final com.smartorder.edu.agent.tool.LearningPlanCreationTool planCreationTool;

    @Override
    public String name() {
        return "manageLearningTask";
    }

    @Override
    public String description() {
        return "创建学习空间、学习路径节点、任务和知识图谱。当用户确认要开始学习某个目标时调用。";
    }

    @Override
    public Class<?> requestType() {
        return ManageLearningTaskRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.TASK;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        ManageLearningTaskRequest req = (ManageLearningTaskRequest) request;
        Long userId = req.getUserId() != null ? req.getUserId() : context.getUserId();

        log.info("[ManageLearningTask] userId={}, description={}", userId, req.getPlanDescription());

        return planCreationTool.createLearningPlan(userId, req.getPlanDescription());
    }

    @Data
    public static class ManageLearningTaskRequest {
        private Long userId;
        private String planDescription;
    }
}
