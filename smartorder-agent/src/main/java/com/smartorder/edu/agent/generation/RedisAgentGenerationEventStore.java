package com.smartorder.edu.agent.generation;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的事件归档实现（生产默认）。
 *
 * <p><b>Key 设计</b>：</p>
 * <ul>
 *   <li>{@code agent:gen:{generationId}:events} — List，按 seq 追加的事件 JSON</li>
 *   <li>{@code agent:gen:{generationId}:meta} — Hash，状态/归属/最后 seq</li>
 *   <li>{@code agent:gen:req:{userId}:{clientRequestId}} — String，幂等键映射</li>
 *   <li>{@code agent:gen:conv:{conversationId}} — String，会话当前生成</li>
 * </ul>
 *
 * <p><b>为什么用 List 而不是 Stream</b>：seq 从 1 开始、逐条追加、只在终态后整体过期，
 * 因此 List 的下标与 seq 一一对应（{@code LRANGE after -1} 正好取到 seq &gt; after）。
 * 比 Stream 少一层 ID 解析，也避免把“业务 seq”和“Redis Stream ID”混用。
 * 读取时仍按事件内 seq 再过滤一次，防止归档缺失导致错位。</p>
 *
 * <p><b>写入是异步镜像</b>（见 {@link AgentGenerationEventMirror}）：本机实时回放走内存日志，
 * Redis 用于进程外/重启后的回放，避免每个 token 都等一次远程 Redis RTT。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "redis", matchIfMissing = true)
public class RedisAgentGenerationEventStore implements AgentGenerationEventStore {

    private static final String KEY_PREFIX = "agent:gen:";

    private final StringRedisTemplate redis;

    private final AgentSseProperties properties;

    public RedisAgentGenerationEventStore(StringRedisTemplate redis, AgentSseProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void saveMeta(AgentGenerationMeta meta) {
        String key = metaKey(meta.getGenerationId());
        redis.opsForHash().putAll(key, meta.toMap());
        expire(key);
    }

    @Override
    public AgentGenerationMeta getMeta(String generationId) {
        Map<Object, Object> raw = redis.opsForHash().entries(metaKey(generationId));
        return AgentGenerationMeta.fromMap(raw);
    }

    @Override
    public void updateState(String generationId, AgentGenerationStatus status, long lastSeq,
                            String errorMessage, Long assistantMessageId) {
        String key = metaKey(generationId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", status.name());
        fields.put("lastSeq", String.valueOf(lastSeq));
        fields.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        if (errorMessage != null) {
            fields.put("errorMessage", errorMessage);
        }
        if (assistantMessageId != null) {
            fields.put("assistantMessageId", String.valueOf(assistantMessageId));
        }
        redis.opsForHash().putAll(key, fields);
        expire(key);
    }

    @Override
    public void appendEvent(String generationId, AgentGenerationEvent event) {
        String key = eventsKey(generationId);
        redis.opsForList().rightPush(key, JSON.toJSONString(event));
        expire(key);
    }

    @Override
    public List<AgentGenerationEvent> readAfter(String generationId, long afterSeq) {
        long from = Math.max(0L, afterSeq);
        List<String> raw = redis.opsForList().range(eventsKey(generationId), from, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AgentGenerationEvent> result = new ArrayList<>(raw.size());
        for (String json : raw) {
            AgentGenerationEvent event = parse(json);
            if (event != null && event.getSeq() > afterSeq) {
                result.add(event);
            }
        }
        return result;
    }

    @Override
    public AgentGenerationEvent readFirstAfter(String generationId, long afterSeq) {
        // List 的下标从 0 开始、事件的 seq 从 1 开始，因此下标 afterSeq 正好是
        // “seq 严格大于 afterSeq”的候选位置；真正是否连续由调用方比较 seq。
        String json = redis.opsForList().index(eventsKey(generationId), Math.max(0L, afterSeq));
        return json == null ? null : parse(json);
    }

    @Override
    public long lastEventSeq(String generationId) {
        String json = redis.opsForList().index(eventsKey(generationId), -1L);
        AgentGenerationEvent event = json == null ? null : parse(json);
        return event != null ? event.getSeq() : 0L;
    }

    @Override
    public void bindClientRequest(Long userId, String clientRequestId, String generationId) {
        String key = KEY_PREFIX + "req:" + userId + ":" + clientRequestId;
        redis.opsForValue().set(key, generationId, retentionHours(), TimeUnit.HOURS);
    }

    @Override
    public String findGenerationByClientRequest(Long userId, String clientRequestId) {
        return redis.opsForValue().get(KEY_PREFIX + "req:" + userId + ":" + clientRequestId);
    }

    @Override
    public void bindConversation(String conversationId, String generationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + "conv:" + conversationId, generationId,
                retentionHours(), TimeUnit.HOURS);
    }

    @Override
    public String findActiveGeneration(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return redis.opsForValue().get(KEY_PREFIX + "conv:" + conversationId);
    }

    @Override
    public void updateConversation(String generationId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = metaKey(generationId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("conversationId", conversationId);
        fields.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().putAll(key, fields);
        expire(key);
    }

    @Override
    public void markReplayDegraded(String generationId) {
        redis.opsForHash().put(metaKey(generationId), "replayable", "0");
    }

    private AgentGenerationEvent parse(String json) {
        try {
            return JSON.parseObject(json, AgentGenerationEvent.class);
        } catch (Exception e) {
            log.warn("[GenerationStore] 事件反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String metaKey(String generationId) {
        return KEY_PREFIX + generationId + ":meta";
    }

    private String eventsKey(String generationId) {
        return KEY_PREFIX + generationId + ":events";
    }

    private void expire(String key) {
        redis.expire(key, retentionHours(), TimeUnit.HOURS);
    }

    private long retentionHours() {
        return Math.max(1, properties.getReplayRetentionHours());
    }
}
