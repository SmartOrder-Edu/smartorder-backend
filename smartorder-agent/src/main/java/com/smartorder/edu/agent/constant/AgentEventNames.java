package com.smartorder.edu.agent.constant;

/**
 * 生成事件名常量。
 *
 * <p>大部分事件名来自 {@link com.smartorder.edu.agent.model.enums.AgentEventType}，
 * 这里只集中放“协议层”用到的名字，供生成任务运行时与 SSE 出口共用。</p>
 */
public final class AgentEventNames {

    /** 生成任务已开始（首包，携带 generationId） */
    public static final String GENERATION_STARTED = "agent.generation.started";

    /** 生成结束（唯一的终态事件） */
    public static final String DONE = "done";

    /** 生成中断（进程消失后的补发事件） */
    public static final String ERROR = "agent.error";

    private AgentEventNames() {
    }
}
