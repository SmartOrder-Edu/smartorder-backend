package com.smartorder.edu.agent.model.enums;

/**
 * 一次 Agent 生成任务的生命周期状态。
 *
 * <p>与“会话状态”“计划状态”不同：生成任务只表示“这次请求的模型输出是否还在产生”。
 * 计划被暂停（PAUSED_CLARIFICATION 等）时，本次生成段依旧会正常结束（COMPLETED），
 * 计划是否继续由计划运行态单独表达。</p>
 */
public enum AgentTaskStatus {

    /** 记录已创建，执行线程尚未开始 */
    CREATED,

    /** 正在生成，事件持续追加 */
    RUNNING,

    /** 正常结束（已收到终态 done） */
    COMPLETED,

    /** 生成失败（模型/工具/内部异常），done 中带有错误信息 */
    FAILED,

    /** 用户主动取消 */
    CANCELLED,

    /** 执行进程已消失（重启、崩溃），无法再续算，只能回放已产生的事件 */
    INTERRUPTED;

    /**
     * 是否终态。终态之后不再接受新事件，订阅者回放完积压事件后连接即关闭。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }

    /**
     * 宽松解析：不认识的值返回 {@code null}，避免历史脏数据导致反序列化失败。
     */
    public static AgentTaskStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
