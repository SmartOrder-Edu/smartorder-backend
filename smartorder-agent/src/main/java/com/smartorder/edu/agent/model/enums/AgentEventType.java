package com.smartorder.edu.agent.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 结构化 SSE 事件类型枚举
 */
@Getter
@AllArgsConstructor
public enum AgentEventType {

    /**
     * 生成任务已开始（首包）。
     * <p>断线重连后客户端会在回放里再次看到它，因此 payload 里带 generationId，
     * 用于确认“回放的确实是我要续的那一次生成”。</p>
     */
    GENERATION_STARTED("agent.generation.started", "生成任务已开始", false),

    TRACE_STARTED("agent.trace.started", "追踪开始", true),

    /**
     * 会话已创建（首条消息时下发，前端据此把会话 upsert 到列表头部）
     */
    CONVERSATION_CREATED("agent.conversation.created", "会话已创建", true),

    /**
     * 会话标题已由模型压缩生成（异步下发，前端据此更新列表项标题）。
     * <p>
     * 不持久化：标题本身已落在 conversation.title，追踪日志里重复记录没有价值。
     */
    CONVERSATION_TITLE("agent.conversation.title", "会话标题已生成", false),

    /**
     * 助手占位消息已创建（status=RUNNING），前端据此渲染"生成中"气泡
     */
    MESSAGE_CREATED("agent.message.created", "消息已创建", false),

    ROUTE_COMPLETED("agent.route.completed", "意图路由完成", true),

    THINK_DELTA("agent.think.delta", "智能体思考片段", false),

    TOOL_STARTED("agent.tool.started", "工具调用开始", true),
    TOOL_COMPLETED("agent.tool.completed", "工具调用完成", true),
    TOOL_FAILED("agent.tool.failed", "工具调用失败", true),

    TEXT_DELTA("agent.text.delta", "最终回复文本流", false),

    PLAN_CREATED("agent.plan.created", "规划草稿创建", true),
    PLAN_CONFIRMED("agent.plan.confirmed", "规划已确认", true),
    LEARNING_PLAN_CREATED("agent.learning_plan.created", "学习计划草稿已创建", true),

    MINDMAP_CREATED("agent.mindmap.created", "思维导图已生成", true),

    PHASE_CHANGE("agent.phase.change", "阶段切换", true),
    STATUS("agent.status", "状态提示", true),

    RAG_CHUNKS("agent.rag.chunks", "RAG检索结果", true),

    // ====== PlanAndExecute 事件 ======
    EXECUTION_STEP_STARTED("agent.plan.task.started", "执行步骤开始执行", true),
    EXECUTION_STEP_COMPLETED("agent.plan.task.completed", "执行步骤执行完成", true),
    EXECUTION_STEP_FAILED("agent.plan.task.failed", "执行步骤执行失败", true),
    PLAN_PAUSED("agent.plan.paused", "计划暂停（澄清/确认门）", true),
    GATE_ANSWERED("agent.gate.answered", "门控问题已回答", true),
    PLAN_RESUMED("agent.plan.resumed", "计划从暂停点恢复", true),

    ERROR("agent.error", "错误事件", true),

    TRACE_COMPLETED("agent.trace.completed", "追踪完成", true),
    DONE("done", "SSE结束", false);

    private final String eventName;
    private final String description;
    private final boolean persistable;
}
