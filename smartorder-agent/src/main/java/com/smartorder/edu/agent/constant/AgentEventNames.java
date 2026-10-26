package com.smartorder.edu.agent.constant;

/**
 * 任务事件名常量。
 *
 * <p>大部分事件名来自 {@link com.smartorder.edu.agent.model.enums.AgentEventType}，
 * 这里只集中放“协议层”用到的名字，供对话任务运行时与 SSE 出口共用。</p>
 */
public final class AgentEventNames {

    /** 对话任务已开始（首包，携带 taskId） */
    public static final String TASK_STARTED = "agent.task.started";

    /** 生成结束（唯一的终态事件） */
    public static final String DONE = "done";

    /** 生成中断（进程消失后的补发事件） */
    public static final String ERROR = "agent.error";

    private AgentEventNames() {
    }
}
