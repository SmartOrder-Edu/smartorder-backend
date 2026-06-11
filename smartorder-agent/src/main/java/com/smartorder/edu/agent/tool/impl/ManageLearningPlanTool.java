package com.smartorder.edu.agent.tool.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
 * 管理学习规划工具——创建完整的学习规划草稿
 * <p>
 * 替代旧版 ManageLearningTaskTool（只保存简单草稿，无 phases，会 NPE）。
 * 本工具接收完整的规划参数，保存为 LEGACY 类型草稿，需用户确认后才创建学习空间。
 * <p>
 * 风险等级 WRITE_CONFIRMED：创建学习空间属于有副作用的操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManageLearningPlanTool implements ToolHandler {

    private final PlanDraftService draftService;

    @Override
    public String name() {
        return "manageLearningPlan";
    }

    @Override
    public String description() {
        return "创建学习规划草稿，包含学习空间、学习路径和任务。"
                + "必须传入学习目标描述，可选传入空间名称和类型。"
                + "规划保存为草稿，需要用户确认后才会创建学习空间。";
    }

    @Override
    public Class<?> requestType() {
        return ManageLearningPlanRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PLAN;
    }

    @Override
    public ToolCategory.RiskLevel riskLevel() {
        return ToolCategory.RiskLevel.WRITE_CONFIRMED;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        ManageLearningPlanRequest req = (ManageLearningPlanRequest) request;
        Long userId = context.getUserId();

        String goalDescription = req.getGoalDescription();
        if (goalDescription == null || goalDescription.isBlank()) {
            return Map.of(
                    "success", false,
                    "error", "goalDescription 不能为空，请提供用户的学习目标描述"
            );
        }

        log.info("[ManageLearningPlan] userId={}, goal={}", userId, goalDescription);

        // 构建完整的 plan JSON（包含 phases，避免 NPE）
        JSONObject planJson = new JSONObject();
        planJson.put("spaceName", req.getSpaceName() != null ? req.getSpaceName() : goalDescription);
        planJson.put("description", goalDescription);
        planJson.put("type", req.getSpaceType() != null ? req.getSpaceType() : "course");
        planJson.put("goals", JSONArray.toJSONString(java.util.List.of(goalDescription)));

        // 如果有 phases 参数，尝试解析
        if (req.getPhases() != null && !req.getPhases().isBlank()) {
            try {
                JSONArray phasesArr = JSONArray.parseArray(req.getPhases());
                planJson.put("phases", phasesArr);
            } catch (Exception e) {
                log.warn("[ManageLearningPlan] phases 解析失败，将不包含阶段信息: {}", e.getMessage());
            }
        }

        // 保存完整草稿
        String draftId = draftService.saveDraft(
                context.getConversationId(),
                context.getTraceId(),
                userId,
                planJson);

        log.info("[ManageLearningPlan] 规划草稿已保存，等待用户确认: draftId={}", draftId);

        return Map.of(
                "success", true,
                "draftId", draftId,
                "message", "学习规划草稿已生成，请让用户确认是否创建学习空间。",
                "needsConfirmation", true,
                "confirmEndpoint", "/agent/plan/confirm"
        );
    }

    @Data
    public static class ManageLearningPlanRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间名称，如'数据结构学习'")
        private String spaceName;

        @dev.langchain4j.model.output.structured.Description("学习目标描述，例如'学习操作系统'、'准备考研数据结构'")
        private String goalDescription;

        @dev.langchain4j.model.output.structured.Description("空间类型：course/exam/project/career/competition")
        private String spaceType;

        @dev.langchain4j.model.output.structured.Description("学习阶段列表，JSON数组格式，可选项")
        private String phases;
    }
}
