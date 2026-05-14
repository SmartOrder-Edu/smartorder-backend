package com.smartorder.edu.agent.trace;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 结构化 SSE 事件类型枚举
 */
@Getter
@AllArgsConstructor
public enum AgentEventType {

    TRACE_STARTED("agent.trace.started", "追踪开始"),

    ROUTE_COMPLETED("agent.route.completed", "意图路由完成"),

    THINK_DELTA("agent.think.delta", "智能体思考片段"),

    TOOL_STARTED("agent.tool.started", "工具调用开始"),
    TOOL_COMPLETED("agent.tool.completed", "工具调用完成"),
    TOOL_FAILED("agent.tool.failed", "工具调用失败"),

    TEXT_DELTA("agent.text.delta", "最终回复文本流"),

    PLAN_CREATED("agent.plan.created", "规划草稿创建"),
    PLAN_CONFIRMED("agent.plan.confirmed", "规划已确认"),

    PHASE_CHANGE("agent.phase.change", "阶段切换"),
    STATUS("agent.status", "状态提示"),

    SUB_AGENT_STARTED("agent.sub_agent.started", "子智能体开始"),
    SUB_AGENT_COMPLETED("agent.sub_agent.completed", "子智能体完成"),
    SUB_AGENT_FAILED("agent.sub_agent.failed", "子智能体失败"),

    ERROR("agent.error", "错误事件"),

    TRACE_COMPLETED("agent.trace.completed", "追踪完成"),
    DONE("done", "SSE结束");

    private final String eventName;
    private final String description;
}
