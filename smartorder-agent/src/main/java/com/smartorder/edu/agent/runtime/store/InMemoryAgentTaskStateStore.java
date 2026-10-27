package com.smartorder.edu.agent.runtime.store;

import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.AgentTaskState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版任务运行时状态存储——单机开发/单测使用，语义与 Redis 版一致。
 *
 * <p>这里的 {@code Map} 是<b>被模拟的 Redis</b>，不是"省掉 Redis 的本地状态"：
 * 生产环境把本类换成 {@link RedisAgentTaskStateStore} 后，注册表一行都不用改。
 * 因此它保留 TTL/节点租约这些概念，只是用时间戳判断是否过期。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "memory")
public class InMemoryAgentTaskStateStore implements AgentTaskStateStore {

    private final Map<String, Entry> states = new ConcurrentHashMap<>();

    private final Map<String, Long> interruptedMarkers = new ConcurrentHashMap<>();

    private final Map<String, Boolean> degradedMarkers = new ConcurrentHashMap<>();

    private final Map<String, String> conversationLatest = new ConcurrentHashMap<>();

    private final Map<String, Long> nodeLeases = new ConcurrentHashMap<>();

    /** 测试用：模拟"别的实例还活着"（Redis 版里对应 agent:node:{id} 这个 TTL key） */
    private final Set<String> extraAliveNodes = ConcurrentHashMap.newKeySet();

    /** 本节点的租约是否有效；停机释放后为 false */
    private volatile boolean ownLeaseActive = true;

    private final String nodeId = "memory-node";

    /** 内存实现不会真的过期，但语义上仍保留"多久算过期" */
    private final long ttlMillis;

    public InMemoryAgentTaskStateStore() {
        this(24L);
    }

    public InMemoryAgentTaskStateStore(long retentionHours) {
        this.ttlMillis = Math.max(1L, retentionHours) * 60 * 60 * 1000L;
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
        if (state.getUpdatedAt() <= 0) {
            state.setUpdatedAt(System.currentTimeMillis());
        }
        states.put(state.getTaskId(), new Entry(copy(state), System.currentTimeMillis() + ttlMillis));
        if (state.getConversationId() != null && !state.getConversationId().isBlank()) {
            conversationLatest.put(state.getConversationId(), state.getTaskId());
        }
        if (!state.isTerminal()) {
            interruptedMarkers.remove(state.getTaskId());
        }
    }

    @Override
    public AgentTaskState load(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Entry entry = states.get(taskId);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            states.remove(taskId, entry);
            return null;
        }
        return copy(entry.state);
    }

    @Override
    public void touch(String taskId, long lastSeq) {
        Entry entry = states.get(taskId);
        if (entry == null) {
            return;
        }
        entry.state.setLastSeq(Math.max(entry.state.getLastSeq(), lastSeq));
        entry.state.setUpdatedAt(System.currentTimeMillis());
        entry.expireAt = System.currentTimeMillis() + ttlMillis;
    }

    @Override
    public boolean requestCancel(String taskId) {
        Entry entry = states.get(taskId);
        if (entry == null) {
            return false;
        }
        entry.state.setCancelRequested(true);
        entry.state.setUpdatedAt(System.currentTimeMillis());
        return true;
    }

    @Override
    public boolean isCancelRequested(String taskId) {
        Entry entry = states.get(taskId);
        return entry != null && entry.state.isCancelRequested();
    }

    @Override
    public boolean markInterruptedOnce(String taskId) {
        return interruptedMarkers.putIfAbsent(taskId, System.currentTimeMillis()) == null;
    }

    @Override
    public void releaseInterruptedMarker(String taskId) {
        interruptedMarkers.remove(taskId);
    }

    @Override
    public boolean markReplayDegraded(String taskId) {
        Entry entry = states.get(taskId);
        if (entry != null) {
            entry.state.setReplayable(false);
        }
        return degradedMarkers.putIfAbsent(taskId, Boolean.TRUE) == null;
    }

    @Override
    public void remove(String taskId) {
        if (taskId == null) {
            return;
        }
        states.remove(taskId);
        interruptedMarkers.remove(taskId);
        degradedMarkers.remove(taskId);
        conversationLatest.values().removeIf(taskId::equals);
    }

    @Override
    public Set<String> activeTaskIds() {
        Set<String> active = new LinkedHashSet<>();
        states.entrySet().stream()
                .filter(entry -> !entry.getValue().state.isTerminal())
                .sorted(Comparator.comparingLong(entry -> entry.getValue().state.getUpdatedAt()))
                .forEach(entry -> active.add(entry.getKey()));
        return active;
    }

    @Override
    public Set<String> interruptedTaskIds() {
        Set<String> ids = new LinkedHashSet<>(interruptedMarkers.keySet());
        ids.removeIf(taskId -> {
            Entry entry = states.get(taskId);
            return entry == null || !entry.state.isTerminal();
        });
        return ids;
    }

    @Override
    public boolean isNodeAlive(String nodeId) {
        // 单机内存实现里，本进程默认活着（等价于 Redis 版里自己的租约 key）；
        // 其它节点由测试显式登记
        if (nodeId == null) {
            return false;
        }
        if (this.nodeId.equals(nodeId)) {
            return ownLeaseActive;
        }
        return extraAliveNodes.contains(nodeId);
    }

    /** 测试专用：登记一个"还活着的其它节点"，用来验证跨节点判定 */
    public void registerAliveNode(String nodeId) {
        extraAliveNodes.add(nodeId);
    }

    /** 测试专用：让某个节点的租约过期（模拟进程崩溃） */
    public void expireAliveNode(String nodeId) {
        extraAliveNodes.remove(nodeId);
    }

    @Override
    public void refreshNodeLease() {
        ownLeaseActive = true;
        nodeLeases.put(nodeId, System.currentTimeMillis());
    }

    @Override
    public void releaseNodeLease() {
        ownLeaseActive = false;
        nodeLeases.remove(nodeId);
        extraAliveNodes.remove(nodeId);
    }

    @Override
    public String latestTaskId(String conversationId) {
        return conversationId == null ? null : conversationLatest.get(conversationId);
    }

    @Override
    public void pruneIndexesBefore(long cutoffMillis) {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(entry -> entry.getValue().expireAt < now);
    }

    /** {@link AgentTaskStateStore#markTerminal} 的默认实现即可，这里只保证不会写出终态后又被覆盖 */
    @Override
    public void markTerminal(String taskId, AgentTaskStatus status, long lastSeq,
                             String errorMessage, Long assistantMessageId, boolean replayable) {
        Entry entry = states.get(taskId);
        if (entry == null) {
            return;
        }
        entry.state.setStatus(status.name());
        entry.state.setLastSeq(Math.max(entry.state.getLastSeq(), lastSeq));
        entry.state.setErrorMessage(errorMessage);
        entry.state.setFinishedAt(System.currentTimeMillis());
        entry.state.setUpdatedAt(entry.state.getFinishedAt());
        if (assistantMessageId != null) {
            entry.state.setAssistantMessageId(assistantMessageId);
        }
        entry.state.setReplayable(entry.state.isReplayable() && replayable);
    }

    private AgentTaskState copy(AgentTaskState source) {
        return AgentTaskState.builder()
                .taskId(source.getTaskId())
                .userId(source.getUserId())
                .taskRequestId(source.getTaskRequestId())
                .conversationId(source.getConversationId())
                .traceId(source.getTraceId())
                .userMessageId(source.getUserMessageId())
                .assistantMessageId(source.getAssistantMessageId())
                .assistantSequenceNo(source.getAssistantSequenceNo())
                .title(source.getTitle())
                .status(source.getStatus())
                .lastSeq(source.getLastSeq())
                .errorMessage(source.getErrorMessage())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .finishedAt(source.getFinishedAt())
                .cancelRequested(source.isCancelRequested())
                .replayable(source.isReplayable())
                .nodeId(source.getNodeId())
                .build();
    }

    private static final class Entry {

        private final AgentTaskState state;

        private long expireAt;

        private Entry(AgentTaskState state, long expireAt) {
            this.state = state;
            this.expireAt = expireAt;
        }
    }
}
