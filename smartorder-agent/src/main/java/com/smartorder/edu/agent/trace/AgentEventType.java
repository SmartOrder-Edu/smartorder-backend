package com.smartorder.edu.agent.trace;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 结构化 SSE 事件类型枚举
 */
@Getter
@AllArgsConstructor
public enum AgentEventType {

    TRACE_STARTED("agent.trace.started", "追踪开始", true),

    ROUTE_COMPLETED("agent.route.completed", "意图路由完成", true),

    THINK_DELTA("agent.think.delta", "智能体思考片段", false),

    TOOL_STARTED("agent.tool.started", "工具调用开始", true),
    TOOL_COMPLETED("agent.tool.completed", "工具调用完成", true),
    TOOL_FAILED("agent.tool.failed", "工具调用失败", true),

    TEXT_DELTA("agent.text.delta", "最终回复文本流", false),

    PLAN_CREATED("agent.plan.created", "规划草稿创建", true),
    PLAN_CONFIRMED("agent.plan.confirmed", "规划已确认", true),

    PHASE_CHANGE("agent.phase.change", "阶段切换", true),
    STATUS("agent.status", "状态提示", true),

    // ====== PlanThenReAct 事件 ======
    PLAN_TASK_STARTED("agent.plan.task.started", "计划任务开始执行", true),
    PLAN_TASK_COMPLETED("agent.plan.task.completed", "计划任务执行完成", true),
    PLAN_TASK_FAILED("agent.plan.task.failed", "计划任务执行失败", true),
    PLAN_PAUSED("agent.plan.paused", "计划暂停（澄清/确认门）", true),
    PLAN_RESUMED("agent.plan.resumed", "计划从暂停点恢复", true),

    ERROR("agent.error", "错误事件", true),

    TRACE_COMPLETED("agent.trace.completed", "追踪完成", true),
    DONE("done", "SSE结束", false);

    private final String eventName;
    private final String description;
    private final boolean persistable;
}
