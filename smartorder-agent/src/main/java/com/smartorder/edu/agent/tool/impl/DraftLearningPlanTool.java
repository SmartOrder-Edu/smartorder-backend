package com.smartorder.edu.agent.tool.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 学习计划草稿工具——生成并保存完整的学习计划草稿，用于用户审阅。
 * <p>
 * 与 {@link ManageLearningPlanTool} 的关键区别：
 * <ul>
 *   <li>本工具只保存草稿（{@code WRITE_DRAFT}），不创建学习空间实体</li>
 *   <li>本工具的 Request DTO 包含完整字段：goals、startDate、targetEndDate、knowledgeGraph</li>
 *   <li>执行后发射 {@code agent.learning_plan.created} 事件，前端展示学习计划预览</li>
 * </ul>
 * <p>
 * 用户审阅后通过 {@code /agent/plan/confirm} 确认创建学习空间，
 * 后端对 LEARNING_PLAN 类型草稿调用 {@code PlanConfirmService.createEntitiesOutlineOnly()}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftLearningPlanTool implements ToolHandler {

    private final PlanDraftService draftService;
    private final TraceEventDispatcher dispatcher;

    @Override
    public String name() {
        return "draftLearningPlan";
    }

    @Override
    public String description() {
        return "生成并保存完整的学习计划草稿，供用户审阅。"
                + "必须传入 spaceName、goalDescription、type、goals（学习目标数组）、"
                + "startDate、targetEndDate、phases（完整 JSON 数组，每阶段含 phaseIndex/phaseName/"
                + "knowledgePoints/weeks/resources）、"
                + "knowledgeGraph（完整 JSON 对象，nodes 含 name/description/category/importance，"
                + "edges 含 from/to/type）。"
                + "此操作仅保存草稿，不创建学习空间，用户审阅后才确认创建。";
    }

    @Override
    public Class<?> requestType() {
        return DraftLearningPlanRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PLAN;
    }

    @Override
    public ToolCategory.RiskLevel riskLevel() {
        return ToolCategory.RiskLevel.WRITE_DRAFT;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        DraftLearningPlanRequest req = (DraftLearningPlanRequest) request;
        Long userId = context.getUserId();

        String goalDescription = req.getGoalDescription();
        if (goalDescription == null || goalDescription.isBlank()) {
            return Map.of(
                    "success", false,
                    "error", "goalDescription 不能为空，请提供用户的学习目标描述"
            );
        }

        log.info("[DraftLearningPlan] userId={}, goal={}", userId, goalDescription);

        // 构建完整的 plan JSON
        JSONObject planJson = new JSONObject();
        planJson.put("spaceName", req.getSpaceName() != null ? req.getSpaceName() : goalDescription);
        planJson.put("description", goalDescription);
        planJson.put("type", req.getType() != null ? req.getType() : "course");

        // goals: 可能是 JSON 数组字符串或 null
        if (req.getGoals() != null && !req.getGoals().isBlank()) {
            try {
                planJson.put("goals", JSONArray.parseArray(req.getGoals()));
            } catch (Exception e) {
                planJson.put("goals", JSONArray.parseArray("[]"));
            }
        } else {
            planJson.put("goals", JSONArray.parseArray("[]"));
        }

        planJson.put("startDate", req.getStartDate());
        planJson.put("targetEndDate", req.getTargetEndDate());

        // phases: 完整 JSON 数组
        if (req.getPhases() != null && !req.getPhases().isBlank()) {
            try {
                planJson.put("phases", JSONArray.parseArray(req.getPhases()));
            } catch (Exception e) {
                log.warn("[DraftLearningPlan] phases 解析失败: {}", e.getMessage());
            }
        }

        // knowledgeGraph: 完整 JSON 对象
        if (req.getKnowledgeGraph() != null && !req.getKnowledgeGraph().isBlank()) {
            try {
                planJson.put("knowledgeGraph", JSONObject.parseObject(req.getKnowledgeGraph()));
            } catch (Exception e) {
                log.warn("[DraftLearningPlan] knowledgeGraph 解析失败: {}", e.getMessage());
            }
        }

        // 保存为 LEARNING_PLAN 草稿
        String draftId = draftService.saveLearningPlanDraft(
                context.getConversationId(), context.getTraceId(), userId, planJson);

        // 发射 agent.learning_plan.created 事件
        if (context.getRuntimeContext() != null && context.getEmitter() != null) {
            dispatcher.emit(context.getEmitter(), context.getRuntimeContext(),
                    AgentEventType.LEARNING_PLAN_CREATED, Map.of(
                            "draftId", draftId,
                            "plan", planJson,
                            "needsConfirmation", true
                    ));
        }

        log.info("[DraftLearningPlan] 学习计划草稿已生成: draftId={}, spaceName={}",
                draftId, req.getSpaceName());

        return Map.of(
                "success", true,
                "draftId", draftId,
                "plan", planJson,
                "message", "学习计划草稿已生成，请让用户审阅是否创建学习空间。",
                "needsConfirmation", true
        );
    }

    @Data
    public static class DraftLearningPlanRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间名称，如'数据结构学习'")
        private String spaceName;

        @dev.langchain4j.model.output.structured.Description("学习目标描述，例如'学习操作系统'、'准备考研数据结构'")
        private String goalDescription;

        @dev.langchain4j.model.output.structured.Description("空间类型：course/exam/project/career/competition")
        private String type;

        @dev.langchain4j.model.output.structured.Description("学习目标列表，JSON数组格式，如[\"理解进程调度\",\"掌握内存管理\"]")
        private String goals;

        @dev.langchain4j.model.output.structured.Description("计划开始日期，ISO格式 YYYY-MM-DD，如'2026-06-15'")
        private String startDate;

        @dev.langchain4j.model.output.structured.Description("目标完成日期，ISO格式 YYYY-MM-DD，如'2026-09-15'")
        private String targetEndDate;

        @dev.langchain4j.model.output.structured.Description("学习阶段列表，完整 JSON 数组。每阶段含 phaseIndex(序号)/phaseName(阶段名称)/knowledgePoints(知识点列表,字符串数组)/weeks(周数,整数)/resources(资源列表,每项含 resourceType/title)")
        private String phases;

        @dev.langchain4j.model.output.structured.Description("知识图谱 JSON 对象。含 nodes(节点数组,每个节点含 name/description/category/importance) 和 edges(边数组,每条含 from/to/type)。nodes 中的 name 必须唯一，edges 中的 from/to 必须对应 nodes 中的 name")
        private String knowledgeGraph;
    }
}
