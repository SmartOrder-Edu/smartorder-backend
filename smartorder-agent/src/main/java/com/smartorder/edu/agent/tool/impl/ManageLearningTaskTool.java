package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 管理学习任务工具——只生成规划草稿，不直接创建学习空间
 *
 * 风险等级 WRITE_CONFIRMED：此工具原本直接创建学习空间（有副作用），
 * 现已改为只保存 draft，用户需通过 /agent/plan/confirm 确认后才真正创建。
 * 执行后返回 confirmation.required 事件，提示用户确认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManageLearningTaskTool implements ToolHandler {

    private final PlanDraftService draftService;

    @Override
    public String name() {
        return "manageLearningTask";
    }

    @Override
    public String description() {
        return "为用户创建学习规划草稿。当用户表达学习目标或想学习某个领域时调用。"
                + "规划保存为草稿，需要用户确认后才会创建学习空间和任务。"
                + "参数 planDescription 应包含用户的目标描述。";
    }

    @Override
    public Class<?> requestType() {
        return ManageLearningTaskRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.TASK;
    }

    /**
     * 覆盖风险等级为 WRITE_DRAFT，因为此工具现在只保存草稿，不产生最终写操作
     */
    @Override
    public ToolCategory.RiskLevel riskLevel() {
        return ToolCategory.RiskLevel.WRITE_DRAFT;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        ManageLearningTaskRequest req = (ManageLearningTaskRequest) request;

        // 禁止模型传入 userId，统一使用上下文中的 userId
        Long userId = context.getUserId();
        String planDescription = req.getPlanDescription();

        if (planDescription == null || planDescription.isBlank()) {
            return Map.of(
                    "success", false,
                    "error", "planDescription 不能为空，请提供用户的学习目标描述"
            );
        }

        log.info("[ManageLearningTask] userId={}, description={}", userId, planDescription);

        // 只保存为草稿，不创建学习空间
        // 实际的规划生成和创建由 PlanExecuteEngine + PlanConfirmService 完成
        String draftId = draftService.saveSimpleDraft(
                context.getConversationId(),
                context.getTraceId(),
                userId,
                planDescription
        );

        log.info("[ManageLearningTask] 规划草稿已保存，等待用户确认: draftId={}", draftId);

        return Map.of(
                "success", true,
                "draftId", draftId,
                "message", "学习规划草稿已生成，请让用户确认是否创建学习空间。",
                "needsConfirmation", true,
                "confirmEndpoint", "/agent/plan/confirm"
        );
    }

    @Data
    public static class ManageLearningTaskRequest {
        @dev.langchain4j.model.output.structured.Description("用户的学习目标描述，例如'学习操作系统'、'准备考研数据结构'")
        private String planDescription;
    }
}
