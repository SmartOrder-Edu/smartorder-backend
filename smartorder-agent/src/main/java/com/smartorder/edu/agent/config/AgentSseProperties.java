package com.smartorder.edu.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话任务 / SSE 断线重连相关配置。
 *
 * <pre>
 * agent:
 *   sse:
 *     event-store: redis          # redis | memory（同时决定任务状态存储的实现）
 *     replay-retention-hours: 24  # 事件 Stream 与任务运行时状态的保留时长
 *     memory-retention-minutes: 5 # 终态任务的本机执行句柄保留多久（便于立即重连）
 *     heartbeat-seconds: 15       # SSE 注释心跳间隔，同时决定 seq 水位刷新频率
 * </pre>
 *
 * <p>{@code replay-retention-hours} 同时是 Redis 里任务状态 Hash 的 TTL：
 * 事件片段和"任务当时是什么状态"必须同生共死，否则回放得到事件、却查不到归属与终态。</p>
 *
 * <p>{@code event-store=memory} 时状态也用内存实现（{@code InMemoryAgentTaskStateStore}），
 * 只适合单机开发与单测。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.sse")
public class AgentSseProperties {

    /**
     * 事件归档实现：redis（默认，Redis Stream，支持跨进程回放）或 memory（单机/测试）。
     */
    private String eventStore = "redis";

    /** 终态任务的事件在 Redis Stream 中保留多少小时 */
    private int replayRetentionHours = 24;

    /** 终态任务在内存中保留多少分钟，便于刚断开的客户端立刻重连 */
    private int memoryRetentionMinutes = 5;

    /** SSE 注释心跳间隔（秒），0 表示关闭 */
    private int heartbeatSeconds = 15;
}
