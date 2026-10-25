package com.smartorder.edu.agent.generation;

/**
 * 订阅校验通过后的“待接入”计划。
 *
 * <p>拆成两段的目的：控制器先拿到 SseEmitter 并返回，浏览器不必等整个 backlog
 * 回放完才收到响应；真正的心跳、补发和实时登记放到异步 attach 阶段。</p>
 *
 * <p>{@code liveGeneration} 与 {@code archivedMeta} 必有其一：前者表示本机还持有
 * 运行时对象，走后内存日志；后者表示只能从归档回放。</p>
 */
public record AgentGenerationSubscriptionPlan(
        AgentGeneration liveGeneration,
        AgentGenerationMeta archivedMeta,
        AgentGenerationSubscription subscription) {

    public static AgentGenerationSubscriptionPlan live(AgentGeneration generation,
                                                       AgentGenerationSubscription subscription) {
        return new AgentGenerationSubscriptionPlan(generation, null, subscription);
    }

    public static AgentGenerationSubscriptionPlan archived(AgentGenerationMeta meta,
                                                           AgentGenerationSubscription subscription) {
        return new AgentGenerationSubscriptionPlan(null, meta, subscription);
    }

    public boolean isLive() {
        return liveGeneration != null;
    }
}
