package com.smartorder.edu.agent.runtime.store;

import com.smartorder.edu.agent.config.AgentSseProperties;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.AgentTaskState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Redis 的任务运行时状态存储（生产默认）。
 *
 * <p><b>Key 设计</b></p>
 * <pre>
 * agent:task:{taskId}:state         HASH   任务运行时状态（status/lastSeq/绑定/cancel/节点）
 * agent:task:{taskId}:interrupted   STRING 中断事件已补发的 SETNX 标记（跨节点去重）
 * agent:task:{taskId}:degraded      STRING 归档降级已上报的 SETNX 标记（日志去重）
 * agent:task:active                 ZSET   正在执行的任务（score = 最后心跳时间）
 * agent:task:interrupted            ZSET   已判定中断的任务（score = 中断时间）
 * agent:task:conv:{cid}:latest      STRING 会话 → 最近一次任务
 * agent:node:{nodeId}               STRING 节点租约（带 TTL，进程崩溃即过期）
 * </pre>
 *
 * <p><b>为什么这些必须放 Redis</b>：它们原本是 {@code AgentTaskRegistry} 里的
 * 一个 {@code Map}（正在执行）和一个 {@code Set}（已补发中断）。放在 JVM 里意味着
 * 状态跟着进程一起消失：重启后无法判断任务是否还在跑，多实例时各节点只认得自己，
 * 取消请求打错节点直接失效。搬到 Redis 后，这三件事都有了唯一答案。</p>
 *
 * <p><b>节点租约</b>：{@code agent:node:{nodeId}} 只靠 TTL 存活，心跳不续就自动过期。
 * 任务状态里记了执行者 nodeId，于是"任务在跑但不在我这台机器"与"执行者已经死了"
 * 可以区分开——后者才允许判定为中断。</p>
 *
 * <p><b>状态字段为什么总是全量覆盖</b>：Hash 用空串表示"未设置"，读的时候再翻译回 null。
 * 这样不会出现"上次写入的 errorMessage 残留到下一次"的脏字段，
 * 也避免为了清空一个字段而多发一次 HDEL。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "redis",
        matchIfMissing = true)
public class RedisAgentTaskStateStore implements AgentTaskStateStore {

    private static final String TASK_PREFIX = "agent:task:";

    private static final String STATE_SUFFIX = ":state";

    private static final String INTERRUPTED_SUFFIX = ":interrupted";

    private static final String DEGRADED_SUFFIX = ":degraded";

    private static final String ACTIVE_INDEX = "agent:task:active";

    private static final String INTERRUPTED_INDEX = "agent:task:interrupted";

    private static final String CONVERSATION_PREFIX = "agent:task:conv:";

    private static final String CONVERSATION_SUFFIX = ":latest";

    private static final String NODE_PREFIX = "agent:node:";

    /** Hash 字段名：与 AgentTaskState 的字段一一对应 */
    private static final String F_TASK_ID = "taskId";

    private static final String F_USER_ID = "userId";

    private static final String F_TASK_REQUEST_ID = "taskRequestId";

    private static final String F_CONVERSATION_ID = "conversationId";

    private static final String F_TRACE_ID = "traceId";

    private static final String F_USER_MESSAGE_ID = "userMessageId";

    private static final String F_ASSISTANT_MESSAGE_ID = "assistantMessageId";

    private static final String F_ASSISTANT_SEQ = "assistantSequenceNo";

    private static final String F_TITLE = "title";

    private static final String F_STATUS = "status";

    private static final String F_LAST_SEQ = "lastSeq";

    private static final String F_ERROR = "errorMessage";

    private static final String F_CREATED_AT = "createdAt";

    private static final String F_UPDATED_AT = "updatedAt";

    private static final String F_FINISHED_AT = "finishedAt";

    private static final String F_CANCEL = "cancelRequested";

    private static final String F_REPLAYABLE = "replayable";

    private static final String F_NODE_ID = "nodeId";

    private final StringRedisTemplate redis;

    private final AgentSseProperties properties;

    /** 本节点唯一标识：容器里 host+pid 可能重复，再拼一段随机值 */
    private final String nodeId = buildNodeId();

    public RedisAgentTaskStateStore(StringRedisTemplate redis, AgentSseProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @PostConstruct
    public void registerLease() {
        refreshNodeLease();
        log.info("[TaskState] 运行时状态存储就绪: nodeId={}, ttl={}h, lease={}s",
                nodeId, retentionHours(), leaseSeconds());
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public void save(AgentTaskState state) {
        if (state == null || state.getTaskId() == null) {
            return;
        }
        String key = stateKey(state.getTaskId());
        long now = System.currentTimeMillis();
        if (state.getUpdatedAt() <= 0) {
            state.setUpdatedAt(now);
        }
        redis.opsForHash().putAll(key, toHash(state));
        redis.expire(key, retention());

        // 索引维护与状态写入放在一起：任何时候读"正在执行"，都不会看到早已终态的任务
        String taskId = state.getTaskId();
        if (state.isTerminal()) {
            redis.opsForZSet().remove(ACTIVE_INDEX, taskId);
            if (state.statusEnum() == AgentTaskStatus.INTERRUPTED) {
                redis.opsForZSet().add(INTERRUPTED_INDEX, taskId,
                        state.getFinishedAt() > 0 ? state.getFinishedAt() : now);
            }
        } else {
            redis.opsForZSet().add(ACTIVE_INDEX, taskId, state.getUpdatedAt());
            redis.opsForZSet().remove(INTERRUPTED_INDEX, taskId);
        }
        if (state.getConversationId() != null && !state.getConversationId().isBlank()) {
            redis.opsForValue().set(conversationKey(state.getConversationId()), taskId, retention());
        }
    }

    @Override
    public AgentTaskState load(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Map<Object, Object> hash = redis.opsForHash().entries(stateKey(taskId));
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        AgentTaskState state = fromHash(taskId, hash);
        // 只有零散字段的 Hash 是历史残留（例如 touch 撞上过期 key），
        // 当成"没有状态"让调用方回源 MySQL，绝不能拿它去判归属——
        // 否则 userId 为空会把合法用户判成 403。
        if (state.getStatus() == null || state.getUserId() == null) {
            log.debug("[TaskState] 忽略残缺的状态记录: taskId={}", taskId);
            return null;
        }
        return state;
    }

    @Override
    public void touch(String taskId, long lastSeq) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String key = stateKey(taskId);
        // 状态已过期就不要再用心跳把它"复活"成残缺 Hash
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_LAST_SEQ, String.valueOf(Math.max(0L, lastSeq)));
        fields.put(F_UPDATED_AT, String.valueOf(now));
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, retention());
        redis.opsForZSet().add(ACTIVE_INDEX, taskId, now);
    }

    @Override
    public boolean requestCancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        String key = stateKey(taskId);
        // 只有状态还在，取消才有接收方；否则客户端应直接按"已结束"处理
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return false;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_CANCEL, "true");
        fields.put(F_UPDATED_AT, String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().putAll(key, fields);
        return true;
    }

    @Override
    public boolean isCancelRequested(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        Object value = redis.opsForHash().get(stateKey(taskId), F_CANCEL);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    @Override
    public boolean markInterruptedOnce(String taskId) {
        Boolean first = redis.opsForValue()
                .setIfAbsent(markerKey(taskId, INTERRUPTED_SUFFIX), "1", retention());
        return Boolean.TRUE.equals(first);
    }

    @Override
    public void releaseInterruptedMarker(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        redis.delete(markerKey(taskId, INTERRUPTED_SUFFIX));
        redis.opsForZSet().remove(INTERRUPTED_INDEX, taskId);
    }

    @Override
    public boolean markReplayDegraded(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        redis.opsForHash().put(stateKey(taskId), F_REPLAYABLE, "false");
        Boolean first = redis.opsForValue()
                .setIfAbsent(markerKey(taskId, DEGRADED_SUFFIX), "1", retention());
        return Boolean.TRUE.equals(first);
    }

    @Override
    public void remove(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        redis.delete(stateKey(taskId));
        redis.delete(markerKey(taskId, INTERRUPTED_SUFFIX));
        redis.delete(markerKey(taskId, DEGRADED_SUFFIX));
        redis.opsForZSet().remove(ACTIVE_INDEX, taskId);
        redis.opsForZSet().remove(INTERRUPTED_INDEX, taskId);
    }

    @Override
    public Set<String> activeTaskIds() {
        Set<String> members = redis.opsForZSet().range(ACTIVE_INDEX, 0, -1);
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }

    @Override
    public Set<String> interruptedTaskIds() {
        Set<String> members = redis.opsForZSet().range(INTERRUPTED_INDEX, 0, -1);
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }

    @Override
    public boolean isNodeAlive(String nodeId) {
        return nodeId != null && Boolean.TRUE.equals(redis.hasKey(NODE_PREFIX + nodeId));
    }

    @Override
    public void refreshNodeLease() {
        redis.opsForValue().set(NODE_PREFIX + nodeId, String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(leaseSeconds()));
    }

    @Override
    public void releaseNodeLease() {
        // 优雅停机：立刻放弃执行者身份，让其它节点马上可以判定任务中断
        redis.delete(NODE_PREFIX + nodeId);
    }

    @Override
    public String latestTaskId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return redis.opsForValue().get(conversationKey(conversationId));
    }

    @Override
    public void pruneIndexesBefore(long cutoffMillis) {
        // 进程被 kill 时不会有任何人来 ZREM，"正在执行"会留下幽灵成员；
        // 心跳线程顺手按时间裁剪，索引就不会无限增长。
        redis.opsForZSet().removeRangeByScore(ACTIVE_INDEX, 0, cutoffMillis);
        redis.opsForZSet().removeRangeByScore(INTERRUPTED_INDEX, 0, cutoffMillis);
    }

    // ============ 内部 ============

    /** 全量字段，缺省的写空串；读的时候空串再翻译回 null */
    private Map<String, String> toHash(AgentTaskState state) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(F_TASK_ID, nullToEmpty(state.getTaskId()));
        hash.put(F_USER_ID, nullToEmpty(state.getUserId()));
        hash.put(F_TASK_REQUEST_ID, nullToEmpty(state.getTaskRequestId()));
        hash.put(F_CONVERSATION_ID, nullToEmpty(state.getConversationId()));
        hash.put(F_TRACE_ID, nullToEmpty(state.getTraceId()));
        hash.put(F_USER_MESSAGE_ID, nullToEmpty(state.getUserMessageId()));
        hash.put(F_ASSISTANT_MESSAGE_ID, nullToEmpty(state.getAssistantMessageId()));
        hash.put(F_ASSISTANT_SEQ, nullToEmpty(state.getAssistantSequenceNo()));
        hash.put(F_TITLE, nullToEmpty(state.getTitle()));
        hash.put(F_STATUS, nullToEmpty(state.getStatus()));
        hash.put(F_LAST_SEQ, String.valueOf(Math.max(0L, state.getLastSeq())));
        hash.put(F_ERROR, nullToEmpty(state.getErrorMessage()));
        hash.put(F_CREATED_AT, String.valueOf(state.getCreatedAt()));
        hash.put(F_UPDATED_AT, String.valueOf(state.getUpdatedAt()));
        hash.put(F_FINISHED_AT, String.valueOf(state.getFinishedAt()));
        hash.put(F_CANCEL, String.valueOf(state.isCancelRequested()));
        hash.put(F_REPLAYABLE, String.valueOf(state.isReplayable()));
        hash.put(F_NODE_ID, nullToEmpty(state.getNodeId()));
        return hash;
    }

    private AgentTaskState fromHash(String taskId, Map<Object, Object> hash) {
        return AgentTaskState.builder()
                .taskId(taskId)
                .userId(toLong(hash.get(F_USER_ID)))
                .taskRequestId(toStr(hash.get(F_TASK_REQUEST_ID)))
                .conversationId(toStr(hash.get(F_CONVERSATION_ID)))
                .traceId(toStr(hash.get(F_TRACE_ID)))
                .userMessageId(toLong(hash.get(F_USER_MESSAGE_ID)))
                .assistantMessageId(toLong(hash.get(F_ASSISTANT_MESSAGE_ID)))
                .assistantSequenceNo(toInteger(hash.get(F_ASSISTANT_SEQ)))
                .title(toStr(hash.get(F_TITLE)))
                .status(toStr(hash.get(F_STATUS)))
                .lastSeq(toLongValue(hash.get(F_LAST_SEQ)))
                .errorMessage(toStr(hash.get(F_ERROR)))
                .createdAt(toLongValue(hash.get(F_CREATED_AT)))
                .updatedAt(toLongValue(hash.get(F_UPDATED_AT)))
                .finishedAt(toLongValue(hash.get(F_FINISHED_AT)))
                .cancelRequested(toBoolean(hash.get(F_CANCEL)))
                .replayable(!hash.containsKey(F_REPLAYABLE) || toBoolean(hash.get(F_REPLAYABLE)))
                .nodeId(toStr(hash.get(F_NODE_ID)))
                .build();
    }

    private String stateKey(String taskId) {
        return TASK_PREFIX + taskId + STATE_SUFFIX;
    }

    private String markerKey(String taskId, String suffix) {
        return TASK_PREFIX + taskId + suffix;
    }

    private String conversationKey(String conversationId) {
        return CONVERSATION_PREFIX + conversationId + CONVERSATION_SUFFIX;
    }

    private Duration retention() {
        return Duration.ofHours(retentionHours());
    }

    private long retentionHours() {
        return Math.max(1, properties.getReplayRetentionHours());
    }

    /** 租约必须显著长于心跳间隔，否则一次 GC 抖动就会被误判成节点死亡 */
    private long leaseSeconds() {
        return Math.max(30L, Math.max(1, properties.getHeartbeatSeconds()) * 3L);
    }

    private static String buildNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        String pid;
        try {
            pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        } catch (Exception e) {
            pid = "0";
        }
        return host + "-" + pid + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 空串 = 未设置 */
    private String toStr(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private Long toLong(Object value) {
        String text = toStr(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        String text = toStr(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long toLongValue(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? 0L : parsed;
    }

    private boolean toBoolean(Object value) {
        String text = toStr(value);
        return text != null && Boolean.parseBoolean(text);
    }
}
