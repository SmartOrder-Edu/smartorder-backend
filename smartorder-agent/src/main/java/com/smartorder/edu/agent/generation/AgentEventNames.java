package com.smartorder.edu.agent.generation;

/**
 * 生成事件名常量。
 *
 * <p>大部分事件名来自 {@code AgentEventType}，这里只集中放“协议层”用到的名字，
 * 避免 generation 包反过来依赖 trace 包造成循环引用。</p>
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
