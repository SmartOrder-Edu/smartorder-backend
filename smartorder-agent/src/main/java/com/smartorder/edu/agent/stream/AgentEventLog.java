package com.smartorder.edu.agent.stream;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每个 Agent 任务独占一条可回放 Redis Stream，多订阅者独立 XREAD。 */
@Service
public class AgentEventLog {
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    /** 固定数量的写入锁避免跨任务全局串行和无限增长的 taskId 锁表。 */
    private final Object[] stripes = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public AgentEventLog(StringRedisTemplate redis, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 数据库行锁跨实例串行写入；先补投影再发布新事件，避免游标越过未落库片段。 */
    public String append(String taskId, String type, Object data) {
        synchronized (stripes[Math.floorMod(taskId.hashCode(), stripes.length)]) {
            // 标记在 XADD 前独立提交；首条事件 SQL 失败后也不能悄悄创建新 Stream。
            boolean first = jdbc.update("UPDATE agent_chat_task SET stream_started=1 "
                    + "WHERE task_id=? AND stream_started=0", taskId) == 1;
            return java.util.Objects.requireNonNull(
                    transaction.execute(status -> appendLocked(taskId, type, data, first)));
        }
    }

    /** 数据库行锁在整个 XADD 与投影期间持有，跨实例保持事件 ID 和内容顺序一致。 */
    private String appendLocked(String taskId, String type, Object data, boolean first) {
            String key = key(taskId);
            // 已有事件却丢失 Stream 时禁止同世代悄悄重建，否则恢复会静默跳过缺口。
            Long count = jdbc.queryForObject("SELECT event_count FROM agent_chat_task WHERE task_id=? FOR UPDATE",
                    Long.class, taskId);
            boolean exists = Boolean.TRUE.equals(redis.hasKey(key));
            if (!exists && (!first || count != null && count > 0)) {
                throw new IllegalStateException("EVENT_LOG_UNAVAILABLE");
            }
            // 上次 XADD 成功但 SQL 失败时，按 Redis ID 顺序补齐缺失投影。
            if (exists) {
                String cursor = jdbc.queryForObject(
                        "SELECT snapshot_event_id FROM agent_chat_task WHERE task_id=?", String.class, taskId);
                projectPending(taskId, cursor == null ? "0-0" : cursor);
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("type", type);
            fields.put("data", JSON.toJSONString(data));
            var id = redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(key));
            if (id == null) throw new IllegalStateException("Redis 未返回事件 ID");
            // 同一个 SQL 原子推进内容与游标；数据库快照可以落后于 Redis，但绝不超前。
            project(taskId, type, JSON.toJSONString(data), id.getValue());
            return id.getValue();
    }

    /** 无阻塞读取未投影事件；任何一条失败都阻止后续 XADD。 */
    private void projectPending(String taskId, String cursor) {
        while (true) {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    StreamReadOptions.empty().count(100),
                    StreamOffset.create(key(taskId), ReadOffset.from(cursor)));
            if (records == null || records.isEmpty()) return;
            for (MapRecord<String, Object, Object> record : records) {
                project(taskId, String.valueOf(record.getValue().get("type")),
                        String.valueOf(record.getValue().get("data")), record.getId().getValue());
                cursor = record.getId().getValue();
            }
        }
    }

    /** 内容与游标在同一 SQL 更新中提交，事件重放和实时写入共用此投影。 */
    private void project(String taskId, String type, String json, String eventId) {
        JSONObject body = JSON.parseObject(json);
        String delta = "agent.text.delta".equals(type) ? body.getString("content") : "";
        int updated = jdbc.update("UPDATE agent_chat_task SET event_count=event_count+1, "
                        + "snapshot_revision=snapshot_revision+1, "
                        + "snapshot_content=CONCAT(COALESCE(snapshot_content,''),?), snapshot_event_id=? WHERE task_id=?",
                delta == null ? "" : delta, eventId, taskId);
        if (updated != 1) throw new IllegalStateException("EVENT_PROJECTION_FAILED");
    }

    /** 从指定游标之后读取，绝不使用 $ 跳过积压事件。 */
    public List<MapRecord<String, Object, Object>> read(String taskId, String after) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                StreamReadOptions.empty().count(100).block(Duration.ofSeconds(10)),
                StreamOffset.create(key(taskId), ReadOffset.from(after)));
        return records == null ? List.of() : records;
    }

    /** 判断日志是否意外消失；首次事件产生前不存在 key 是正常状态。 */
    public boolean isUnavailable(String taskId, long knownEventCount) {
        return knownEventCount > 0 && !Boolean.TRUE.equals(redis.hasKey(key(taskId)));
    }

    /** 任务可靠终结后才设置过期时间，执行中日志永不过期。 */
    public void expireCompleted(String taskId) {
        redis.expire(key(taskId), Duration.ofHours(24));
    }

    /** 补齐 JSON eventId；持久化记录保持重放时稳定不变。 */
    public JSONObject envelope(MapRecord<String, Object, Object> record) {
        JSONObject data = JSON.parseObject(String.valueOf(record.getValue().get("data")));
        data.put("eventId", record.getId().getValue());
        return data;
    }

    private String key(String taskId) {
        return "agent:events:" + taskId;
    }
}
