package com.smartorder.edu.agent.runtime;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.constant.AgentEventNames;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTraceEventDTO;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.store.AgentTaskStateStore;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次对话任务在本机的<b>执行句柄</b>——状态写通 Redis，本机只留不可序列化的东西。
 *
 * <p><b>为什么不再自己持有状态</b>：status、seq 水门、会话/消息绑定、取消标记
 * 这些字段以前是这里的 {@code volatile} 字段，于是它们只存在于创建任务的 JVM 中：
 * 进程重启就没了，别的实例问不到，取消请求打到另一个节点就静默失效。
 * 现在这些字段的唯一权威是 {@link AgentTaskStateStore}（生产为 Redis Hash），
 * 本对象只保留一份<b>写通镜像</b>，用于避免每个 token 都付出一次 Redis RTT。</p>
 *
 * <p><b>仍然必须留在本机的东西</b>（不能搬 Redis，也不必搬）：</p>
 * <ul>
 *   <li>{@link AgentTaskSubscription}：底层是 {@code SseEmitter}，JVM 对象，不可序列化；</li>
 *   <li>内存事件日志 {@code events}：给"本机断线重连"做零延迟回放，
 *       跨进程回放另有 Redis Stream（见 {@code AgentTaskEventStore}）。</li>
 * </ul>
 * <p>换句话说：本对象是<b>连接与回放句柄</b>，不是任务状态的来源。
 * 丢掉它不影响任何查询/取消/中断判定，只需要客户端重连时改走 Stream 回放。</p>
 *
 * <p><b>线程模型</b>：所有"追加事件 / 写订阅者 / 回放"都在同一把锁内完成，
 * 因此每个订阅者看到的事件顺序与 seq 严格一致。Redis 写通一律放在锁外，
 * 不让一次网络往返拖慢正在刷 token 的临界区。</p>
 *
 * <p><b>seq 为什么本地自增</b>：一个任务同一时刻只有一个执行者（单写者），
 * 本地自增天然无冲突；改成每个事件一次 {@code INCR} 会把流式输出拖成
 * "每 token 一次 Redis RTT"，得不偿失。水位通过心跳定期刷进 Redis，
 * 终态时再落一次最终值。</p>
 */
@Slf4j
public class AgentTask {

    /** 跨节点取消的探测间隔：别的节点置位后，本机最迟 1 秒内能感知 */
    private static final long CANCEL_PROBE_MS = 1_000L;

    /**
     * 事件归档回调——在任务锁内调用，保证归档顺序与 seq 顺序一致（通常是异步镜像）。
     */
    @FunctionalInterface
    public interface EventListener {
        void onEvent(AgentTask task, AgentTaskEvent event);
    }

    @Getter
    private final String taskId;

    @Getter
    private final Long userId;

    /** 任务请求幂等键，可为空 */
    @Getter
    private final String taskRequestId;

    @Getter
    private final long createdAt;

    private final AgentTaskStateStore stateStore;

    private final EventListener eventListener;

    private final Object lock = new Object();

    /** 本机回放日志：只服务本进程的订阅者 */
    private final List<AgentTaskEvent> events = new ArrayList<>();

    private final List<AgentTaskSubscription> subscriptions = new ArrayList<>();

    /** Redis 状态的本地写通镜像；热路径只读它，不读 Redis */
    private volatile AgentTaskState state;

    /** 事件序号；只在 {@link #lock} 内自增 */
    private long seq;

    /** 绑定后的运行上下文，仅用于取消与状态同步 */
    private volatile AgentRuntimeContext context;

    private volatile boolean cancelLocal;

    private volatile long lastCancelProbe;

    public AgentTask(AgentTaskState state, AgentTaskStateStore stateStore,
                     EventListener eventListener) {
        this.taskId = state.getTaskId();
        this.userId = state.getUserId();
        this.taskRequestId = state.getTaskRequestId();
        this.createdAt = state.getCreatedAt() > 0 ? state.getCreatedAt() : System.currentTimeMillis();
        this.state = state;
        this.stateStore = stateStore;
        this.eventListener = eventListener;
        this.seq = Math.max(0L, state.getLastSeq());
        this.cancelLocal = state.isCancelRequested();
    }

    // ============ 状态读取（全部走本地镜像） ============

    public AgentTaskStatus getStatus() {
        return state.statusEnum();
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    /**
     * 当前已产生事件的最大 seq（线程安全读）。
     */
    public long getLastSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    public String getConversationId() {
        return state.getConversationId();
    }

    public String getTraceId() {
        return state.getTraceId();
    }

    public Long getUserMessageId() {
        return state.getUserMessageId();
    }

    public Long getAssistantMessageId() {
        return state.getAssistantMessageId();
    }

    public Integer getAssistantSequenceNo() {
        return state.getAssistantSequenceNo();
    }

    public String getTitle() {
        return state.getTitle();
    }

    public String getErrorMessage() {
        return state.getErrorMessage();
    }

    public long getFinishedAt() {
        return state.getFinishedAt();
    }

    public boolean isReplayDegraded() {
        return !state.isReplayable();
    }

    // ============ 状态变更（本地镜像 + Redis 写通） ============

    /**
     * 绑定 Agent 上下文；CREATED → RUNNING 的状态迁移也在这里发生。
     */
    public void bindContext(AgentRuntimeContext ctx) {
        this.context = ctx;
        if (ctx == null) {
            return;
        }
        AgentTaskState snapshot;
        synchronized (lock) {
            if (ctx.getTraceId() != null) {
                state.setTraceId(ctx.getTraceId());
            }
            if (ctx.getConversationId() != null) {
                state.setConversationId(ctx.getConversationId());
            }
            if (state.statusEnum() == AgentTaskStatus.CREATED) {
                state.setStatus(AgentTaskStatus.RUNNING.name());
            }
            if (ctx.isCancelRequested()) {
                state.setCancelRequested(true);
                cancelLocal = true;
            }
            snapshot = snapshotLocked();
        }
        // 一次任务只会绑定一次上下文，这里写一次 Redis 换"别的节点能看到 RUNNING"
        persistQuietly(snapshot);
    }

    public void bindConversation(String cid) {
        if (cid == null || cid.isBlank() || cid.equals(state.getConversationId())) {
            return;
        }
        AgentTaskState snapshot;
        synchronized (lock) {
            state.setConversationId(cid);
            snapshot = snapshotLocked();
        }
        persistQuietly(snapshot);
    }

    /**
     * 事务 A 完成后回填会话、消息与标题；只在任务第一次绑定或恢复绑定时调用。
     */
    public void bindTurn(AgentTurnContext turn) {
        if (turn == null) {
            return;
        }
        AgentTaskState snapshot;
        synchronized (lock) {
            if (turn.getConversationId() != null) {
                state.setConversationId(turn.getConversationId());
            }
            if (turn.getTraceId() != null) {
                state.setTraceId(turn.getTraceId());
            }
            if (turn.getUserMessageId() != null) {
                state.setUserMessageId(turn.getUserMessageId());
            }
            if (turn.getAssistantMessageId() != null) {
                state.setAssistantMessageId(turn.getAssistantMessageId());
            }
            if (turn.getAssistantSequenceNo() > 0) {
                state.setAssistantSequenceNo(turn.getAssistantSequenceNo());
            }
            if (turn.getTitle() != null) {
                state.setTitle(turn.getTitle());
            }
            snapshot = snapshotLocked();
        }
        persistQuietly(snapshot);
    }

    public void bindAssistantMessageId(Long messageId) {
        if (messageId == null) {
            return;
        }
        AgentTaskState snapshot;
        synchronized (lock) {
            state.setAssistantMessageId(messageId);
            snapshot = snapshotLocked();
        }
        persistQuietly(snapshot);
    }

    /**
     * 追加一条业务事件。终态之后返回 null。
     */
    public AgentTaskEvent append(AgentRuntimeContext ctx, AgentEventType eventType, Object payload) {
        return appendNamed(ctx, eventType.getEventName(), payload);
    }

    public AgentTaskEvent appendNamed(AgentRuntimeContext ctx, String eventName, Object payload) {
        AgentTaskState snapshot = null;
        AgentTaskEvent event;
        synchronized (lock) {
            if (state.isTerminal()) {
                return null;
            }
            long next = ++seq;
            event = buildEvent(ctx, eventName, next, payload);
            events.add(event);
            state.setLastSeq(next);
            if (state.statusEnum() == AgentTaskStatus.CREATED) {
                // 只在这一条事件上做一次状态迁移写通，后续 token 不再碰 Redis
                state.setStatus(AgentTaskStatus.RUNNING.name());
                snapshot = snapshotLocked();
            }
            notifyListener(event);
            broadcast(event);
        }
        if (snapshot != null) {
            // Redis 写入放在锁外：一次网络往返不应拖住正在刷 token 的临界区
            persistQuietly(snapshot);
        }
        return event;
    }

    /**
     * 结束本次任务：先落一条 done 事件（带状态），再切终态并关闭所有订阅。
     *
     * @return done 事件；已经终态时返回 null（保证终态只发一次）
     */
    public AgentTaskEvent finish(AgentRuntimeContext ctx,
                                 Map<String, Object> donePayload,
                                 AgentTaskStatus terminalStatus,
                                 String error) {
        List<AgentTaskSubscription> closing;
        AgentTaskEvent done;
        AgentTaskState snapshot;
        synchronized (lock) {
            if (state.isTerminal()) {
                return null;
            }
            long next = ++seq;
            Map<String, Object> payload = new LinkedHashMap<>();
            if (donePayload != null) {
                payload.putAll(donePayload);
            }
            payload.put("taskId", taskId);
            payload.put("status", terminalStatus.name());
            if (error != null && !error.isBlank()) {
                payload.put("error", error);
            }
            // done 保持扁平结构（与历史 SSE 协议一致），不套事件信封
            syncContext(ctx);
            done = new AgentTaskEvent(next, AgentEventNames.DONE,
                    JSON.toJSONString(payload), System.currentTimeMillis());
            events.add(done);
            state.setLastSeq(next);
            notifyListener(done);
            broadcast(done);

            state.setStatus(terminalStatus.name());
            state.setErrorMessage(error);
            state.setFinishedAt(System.currentTimeMillis());
            if (ctx != null && ctx.getAssistantMessageId() != null) {
                state.setAssistantMessageId(ctx.getAssistantMessageId());
            }
            snapshot = snapshotLocked();
            closing = new ArrayList<>(subscriptions);
            subscriptions.clear();
        }
        // 终态是"跨进程可见"的关键：必须在关闭连接前落 Redis，否则别的节点还会以为任务在跑
        persistQuietly(snapshot);
        for (AgentTaskSubscription sub : closing) {
            sub.complete();
        }
        log.info("[Task] 生成结束: taskId={}, status={}, lastSeq={}, subscribers={}",
                taskId, terminalStatus, done.getSeq(), closing.size());
        return done;
    }

    /**
     * 把新订阅接到生成上：先补发 afterSeq 之后的积压事件，再转入实时推送。
     *
     * <p>补发与登记订阅在同一把锁里完成，所以不存在"补发完了但新事件漏掉"的窗口。</p>
     */
    public void attach(AgentTaskSubscription subscription) {
        boolean terminal;
        synchronized (lock) {
            if (!subscription.sendHeartbeat()) {
                subscription.close();
                return;
            }
            for (AgentTaskEvent event : events) {
                if (event.getSeq() <= subscription.getAfterSeq()) {
                    continue;
                }
                if (!subscription.deliver(event)) {
                    log.info("[Task] 回放中断，连接已断: taskId={}, 已补发到 seq={}",
                            taskId, subscription.getLastSentSeq());
                    subscription.complete();
                    return;
                }
            }
            terminal = state.isTerminal();
            if (!terminal) {
                subscriptions.add(subscription);
                log.info("[Task] 订阅已接入: taskId={}, after={}, 订阅数={}",
                        taskId, subscription.getAfterSeq(), subscriptions.size());
                return;
            }
        }
        // 终态生成：回放完（含 done）就可以关掉连接
        subscription.complete();
    }

    public void detach(AgentTaskSubscription subscription) {
        synchronized (lock) {
            subscriptions.remove(subscription);
        }
    }

    /**
     * 给所有订阅者发心跳；失败的订阅直接摘除。
     */
    public void heartbeat() {
        List<AgentTaskSubscription> current;
        synchronized (lock) {
            current = new ArrayList<>(subscriptions);
        }
        for (AgentTaskSubscription sub : current) {
            if (!sub.sendHeartbeat()) {
                detach(sub);
            }
        }
    }

    /**
     * 只发给当前在线连接、不进入回放日志的即时事件（如异步生成的会话标题）。
     */
    public void publishEphemeral(AgentRuntimeContext ctx, String eventName, Object payload) {
        synchronized (lock) {
            if (subscriptions.isEmpty()) {
                return;
            }
            AgentTraceEventDTO dto = buildDto(ctx, eventName, 0, payload);
            AgentTaskEvent event = new AgentTaskEvent(0, eventName,
                    JSON.toJSONString(dto), dto.getTimestamp());
            broadcast(event);
        }
    }

    /**
     * 请求取消：本地立即生效，同时把标记写进 Redis 让其他节点/后续巡检都能看到。
     */
    public void cancel() {
        markCancelRequested();
        try {
            stateStore.requestCancel(taskId);
        } catch (Exception e) {
            log.warn("[Task] 取消标记写入 Redis 失败，仅本机生效: taskId={}, reason={}",
                    taskId, e.getMessage());
        }
    }

    /**
     * 取消标记是否已置位。
     *
     * <p>本地有标记就是有；本地没有时按 1 秒节流去 Redis 探一次——
     * 这样"取消请求打到了另一个节点"也能生效，而不会每个 token 都问一次 Redis。</p>
     */
    public boolean isCancelRequested() {
        if (cancelLocal) {
            return true;
        }
        synchronized (lock) {
            if (state.isCancelRequested()) {
                markCancelRequested();
                return true;
            }
        }
        if (!probeCancelSignal()) {
            return false;
        }
        markCancelRequested();
        return true;
    }

    /**
     * 由注册表巡检线程调用：把 Redis 里的取消标记推进本机上下文。
     *
     * <p>引擎的循环条件读的是 {@code context.cancelRequested}，
     * 只有把标记推到那里，"别的节点取消 → 本机停止生成"才真正生效。</p>
     */
    public void pollCancelSignal() {
        if (cancelLocal || state.isTerminal()) {
            return;
        }
        if (probeCancelSignal()) {
            markCancelRequested();
        }
    }

    public void markReplayDegraded() {
        synchronized (lock) {
            state.setReplayable(false);
        }
        try {
            stateStore.markReplayDegraded(taskId);
        } catch (Exception e) {
            log.warn("[Task] 回放降级标记写入 Redis 失败: taskId={}, reason={}", taskId, e.getMessage());
        }
    }

    // ============ 内部 ============

    private boolean probeCancelSignal() {
        long now = System.currentTimeMillis();
        if (now - lastCancelProbe < CANCEL_PROBE_MS) {
            return false;
        }
        lastCancelProbe = now;
        try {
            return stateStore.isCancelRequested(taskId);
        } catch (Exception e) {
            // Redis 抖动不能反过来中止生成：取消只是"尽力而为"的协作式中断
            log.debug("[Task] 取消信号探测失败: taskId={}, reason={}", taskId, e.getMessage());
            return false;
        }
    }

    private void markCancelRequested() {
        cancelLocal = true;
        synchronized (lock) {
            state.setCancelRequested(true);
        }
        AgentRuntimeContext ctx = this.context;
        if (ctx != null) {
            ctx.setCancelRequested(true);
        }
    }

    /** 必须在持锁时调用：刷新活跃时间并复制一份不可变快照出去写 Redis */
    private AgentTaskState snapshotLocked() {
        state.setUpdatedAt(System.currentTimeMillis());
        return copy(state);
    }

    private void persistQuietly(AgentTaskState snapshot) {
        try {
            stateStore.save(snapshot);
        } catch (Exception e) {
            // 状态写不进去不能让生成线程崩：MySQL 终态与 Redis Stream 仍能兜底，
            // 只是跨进程查询会短暂看到旧状态。
            log.warn("[Task] 任务状态写入 Redis 失败: taskId={}, reason={}", taskId, e.getMessage());
        }
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

    private void notifyListener(AgentTaskEvent event) {
        if (eventListener == null) {
            return;
        }
        try {
            eventListener.onEvent(this, event);
        } catch (Exception e) {
            // 归档失败不能打断生成：降级标记让订阅端知道"可能无法完整回放"
            synchronized (lock) {
                state.setReplayable(false);
            }
            log.error("[Task] 事件归档失败: taskId={}, seq={}", taskId, event.getSeq(), e);
        }
    }

    private void broadcast(AgentTaskEvent event) {
        if (subscriptions.isEmpty()) {
            return;
        }
        List<AgentTaskSubscription> dead = null;
        for (AgentTaskSubscription sub : subscriptions) {
            if (!sub.deliver(event)) {
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(sub);
            }
        }
        if (dead != null) {
            subscriptions.removeAll(dead);
            log.info("[Task] 摘除失效订阅: taskId={}, 剩余={}", taskId, subscriptions.size());
            for (AgentTaskSubscription sub : dead) {
                sub.complete();
            }
        }
    }

    private AgentTaskEvent buildEvent(AgentRuntimeContext ctx, String eventName, long seq, Object payload) {
        AgentTraceEventDTO dto = buildDto(ctx, eventName, seq, payload);
        return new AgentTaskEvent(seq, eventName, JSON.toJSONString(dto), dto.getTimestamp());
    }

    private AgentTraceEventDTO buildDto(AgentRuntimeContext ctx, String eventName, long seq, Object payload) {
        syncContext(ctx);
        return AgentTraceEventDTO.builder()
                .taskId(taskId)
                .traceId(state.getTraceId())
                .conversationId(state.getConversationId())
                .userId(userId)
                .mode(ctx != null && ctx.getMode() != null ? ctx.getMode().getCode() : null)
                .eventType(eventName)
                .seq((int) seq)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
    }

    /**
     * 从运行上下文同步会话/追踪/消息标识——这些字段在 openReply 之后才会陆续有值。
     */
    private void syncContext(AgentRuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        if (ctx.getConversationId() != null && !ctx.getConversationId().isBlank()) {
            state.setConversationId(ctx.getConversationId());
        }
        if (ctx.getTraceId() != null && !ctx.getTraceId().isBlank()) {
            state.setTraceId(ctx.getTraceId());
        }
        if (ctx.getAssistantMessageId() != null) {
            state.setAssistantMessageId(ctx.getAssistantMessageId());
        }
    }
}
