package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.service.ConversationMessageService;
import com.smartorder.edu.agent.service.PendingReplyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的“待收尾回复”存储（生产默认）。
 *
 * <p>Key：{@code agent:reply:{traceId}}，值是一份 JSON 快照，TTL 30 分钟。
 * 用 TTL 代替旧实现的 {@code Map} + 懒清理：进程崩在一轮问答中间时，
 * 这份状态会自己消失，不会像内存 Map 那样把占位行永久吊在 RUNNING。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "redis",
        matchIfMissing = true)
public class RedisPendingReplyStore implements PendingReplyStore {

    /** 一轮问答的正常上限是分钟级；30 分钟足够覆盖重试与排队 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "agent:reply:";

    private final StringRedisTemplate redis;

    @Override
    public void save(String traceId, ConversationMessageService.PendingReply reply) {
        if (traceId == null || reply == null) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + traceId, JSON.toJSONString(reply), TTL);
    }

    @Override
    public ConversationMessageService.PendingReply load(String traceId) {
        if (traceId == null) {
            return null;
        }
        String value;
        try {
            value = redis.opsForValue().get(KEY_PREFIX + traceId);
        } catch (Exception e) {
            log.warn("[PendingReply] Redis 读取失败: traceId={}, reason={}", traceId, e.getMessage());
            return null;
        }
        if (value == null) {
            return null;
        }
        try {
            return JSON.parseObject(value, ConversationMessageService.PendingReply.class);
        } catch (Exception e) {
            log.warn("[PendingReply] 待收尾状态解析失败，按不存在处理: traceId={}, reason={}",
                    traceId, e.getMessage());
            return null;
        }
    }

    @Override
    public void remove(String traceId) {
        if (traceId == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + traceId);
        } catch (Exception e) {
            log.warn("[PendingReply] Redis 删除失败（TTL 会兜底）: traceId={}, reason={}",
                    traceId, e.getMessage());
        }
    }

    @Override
    public boolean exists(String traceId) {
        return traceId != null && Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + traceId));
    }
}
