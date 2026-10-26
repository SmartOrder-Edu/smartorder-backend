package com.smartorder.edu.agent.runtime.store;

import com.smartorder.edu.agent.config.AgentSseProperties;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis Stream 的事件归档实现（生产默认）。
 *
 * <p><b>Key 设计</b>：{@code agent:task:{taskId}:events}，每个任务一条独立 Stream。
 * 任务元数据不在这里，统一存 MySQL {@code agent_chat_task}。</p>
 *
 * <p><b>为什么用 Stream</b>：XADD 天然按写入顺序生成消息 ID，XREAD 可以按 ID
 * 精确补发；即便进程重启，客户端仍能带着“已应用的 seq”继续读取 Redis 中的消息片段。</p>
 *
 * <p><b>seq 与 Stream ID 的对齐</b>：业务 seq 从 1 开始严格递增，写入时显式使用
 * {@code 0-seq} 作为 Stream ID。因此 {@code seq > after} 与
 * {@code Stream ID > 0-after} 完全等价，前端仍然只认数字 seq，不需要理解 Redis ID。</p>
 *
 * <p><b>写入是异步镜像</b>（见 {@code AgentTaskEventMirror}）：本机实时回放走内存日志，
 * Redis 用于进程外/重启后的回放，避免每个 token 都等一次远程 Redis RTT。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "redis", matchIfMissing = true)
public class RedisAgentTaskEventStore implements AgentTaskEventStore {

    private static final String KEY_PREFIX = "agent:task:";

    private static final int READ_BATCH = 500;

    private final StringRedisTemplate redis;

    private final AgentSseProperties properties;

    public RedisAgentTaskEventStore(StringRedisTemplate redis, AgentSseProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void appendEvent(String taskId, AgentTaskEvent event) {
        String key = eventsKey(taskId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("seq", String.valueOf(event.getSeq()));
        fields.put("event", event.getEventName());
        fields.put("data", event.getData());
        fields.put("ts", String.valueOf(event.getTimestamp()));

        MapRecord<String, String, String> record = StreamRecords.mapBacked(fields)
                .withStreamKey(key)
                .withId(RecordId.of(writeId(event.getSeq())));
        RecordId written = redis.opsForStream().add(record);
        if (written == null) {
            throw new IllegalStateException("Redis XADD 未返回事件 ID: taskId=" + taskId
                    + ", seq=" + event.getSeq());
        }
        // 每个任务独立 TTL：只要还在产生事件，Stream 就不会在中途过期。
        redis.expire(key, retentionHours(), TimeUnit.HOURS);
    }

    @Override
    public List<AgentTaskEvent> readAfter(String taskId, long afterSeq) {
        String key = eventsKey(taskId);
        long cursorSeq = Math.max(0L, afterSeq);
        String cursor = afterId(cursorSeq);
        List<AgentTaskEvent> result = new ArrayList<>();
        while (true) {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    StreamReadOptions.empty().count(READ_BATCH),
                    StreamOffset.create(key, ReadOffset.from(cursor)));
            if (records == null || records.isEmpty()) {
                return result;
            }
            for (MapRecord<String, Object, Object> record : records) {
                AgentTaskEvent event = parse(record);
                if (event != null && event.getSeq() > cursorSeq) {
                    result.add(event);
                    cursorSeq = event.getSeq();
                }
                cursor = record.getId().getValue();
            }
            if (records.size() < READ_BATCH) {
                return result;
            }
        }
    }

    @Override
    public AgentTaskEvent readFirstAfter(String taskId, long afterSeq) {
        long cursorSeq = Math.max(0L, afterSeq);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                eventsKey(taskId),
                Range.rightUnbounded(Range.Bound.exclusive(afterId(cursorSeq))),
                Limit.limit().count(1));
        if (records == null || records.isEmpty()) {
            return null;
        }
        return parse(records.get(0));
    }

    @Override
    public long lastEventSeq(String taskId) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(
                eventsKey(taskId), Range.unbounded(), Limit.limit().count(1));
        if (records == null || records.isEmpty()) {
            return 0L;
        }
        AgentTaskEvent event = parse(records.get(0));
        return event == null ? 0L : event.getSeq();
    }

    private AgentTaskEvent parse(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> value = record.getValue();
            long seq = longOr(value.get("seq"), record.getId().getSequence());
            long timestamp = longOr(value.get("ts"), 0L);
            return AgentTaskEvent.builder()
                    .seq(seq)
                    .eventName(str(value.get("event")))
                    .data(str(value.get("data")))
                    .timestamp(timestamp)
                    .build();
        } catch (Exception e) {
            log.warn("[TaskStore] Stream 事件解析失败: id={}, reason={}", record.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 写入 ID 必须严格大于 0-0；业务 seq 从 1 开始，因此直接使用 0-seq。
     */
    private String writeId(long seq) {
        return "0-" + Math.max(1L, seq);
    }

    /**
     * 读取游标允许 0-0：{@code after=0} 必须能读到 seq=1 的第一条事件。
     */
    private String afterId(long seq) {
        return "0-" + Math.max(0L, seq);
    }

    private String eventsKey(String taskId) {
        return KEY_PREFIX + taskId + ":events";
    }

    private long retentionHours() {
        return Math.max(1, properties.getReplayRetentionHours());
    }

    private long longOr(Object value, Long fallback) {
        if (value == null) {
            return fallback == null ? 0L : fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback == null ? 0L : fallback;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
