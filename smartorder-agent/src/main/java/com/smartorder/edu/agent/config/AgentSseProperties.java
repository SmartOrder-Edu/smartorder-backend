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
 *     event-store: redis          # redis | memory
 *     replay-retention-hours: 24  # 终态任务的 Redis Stream 保留时长
 *     memory-retention-minutes: 5 # 终态任务在内存中保留多久（便于立即重连）
 *     heartbeat-seconds: 15       # SSE 注释心跳间隔
 * </pre>
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
