package com.smartorder.edu.agent.runtime;

/**
 * 订阅校验通过后的“待接入”计划。
 *
 * <p>拆成两段的目的：控制器先拿到 SseEmitter 并返回，浏览器不必等整个 backlog
 * 回放完才收到响应；真正的心跳、补发和实时登记放到异步 attach 阶段。</p>
 *
 * <p>{@code liveTask} 与 {@code archivedMeta} 必有其一：前者表示本机还持有
 * 运行时对象，走内存日志；后者表示只能从 Redis Stream 回放。</p>
 */
public record AgentTaskSubscriptionPlan(
        AgentTask liveTask,
        AgentTaskMeta archivedMeta,
        AgentTaskSubscription subscription) {

    public static AgentTaskSubscriptionPlan live(AgentTask task,
                                                 AgentTaskSubscription subscription) {
        return new AgentTaskSubscriptionPlan(task, null, subscription);
    }

    public static AgentTaskSubscriptionPlan archived(AgentTaskMeta meta,
                                                     AgentTaskSubscription subscription) {
        return new AgentTaskSubscriptionPlan(null, meta, subscription);
    }

    public boolean isLive() {
        return liveTask != null;
    }
}
