package com.smartorder.edu.agent.runtime;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.constant.AgentEventNames;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTraceEventDTO;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次对话任务的运行时对象——事件日志 + 订阅者列表 + 生命周期状态。
 *
 * <p><b>线程模型</b>：所有“追加事件 / 写订阅者 / 回放”都在同一把锁内完成，
 * 因此每个订阅者看到的事件顺序与 seq 严格一致；连接的建立和断开不影响执行线程。</p>
 *
 * <p><b>seq 由本对象分配</b>，而不是由调用方传入：只有分配、写入日志、发送三步
 * 在同一临界区里，回放游标才可能是精确的。</p>
 *
 * <p><b>已经终态后追加的事件会被丢弃</b>（返回 null）。标题补全这类“迟到的锦上添花”
 * 事件必须走 {@link #publishEphemeral}，否则会出现在 done 之后，破坏回放的终态语义。</p>
 */
@Slf4j
public class AgentTask {

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

    /** 客户端幂等键，可为空 */
    @Getter
    private final String clientRequestId;

    @Getter
    private final long createdAt;

    private final EventListener eventListener;

    private final Object lock = new Object();

    private final List<AgentTaskEvent> events = new ArrayList<>();

    private final List<AgentTaskSubscription> subscriptions = new ArrayList<>();

    private long seq;

    private volatile AgentTaskStatus status = AgentTaskStatus.CREATED;

    @Getter
    private volatile String conversationId;

    @Getter
    private volatile String traceId;

    @Getter
    private volatile Long userMessageId;

    @Getter
    private volatile Long assistantMessageId;

    @Getter
    private volatile Integer assistantSequenceNo;

    /** 本轮兜底标题（首条消息时为截断的用户消息） */
    @Getter
    private volatile String title;

    @Getter
    private volatile String errorMessage;

    @Getter
    private volatile long finishedAt;

    @Getter
    private volatile boolean cancelRequested;

    @Getter
    private volatile boolean replayDegraded;

    /** 绑定后的运行上下文，仅用于取消与状态同步 */
    private volatile AgentRuntimeContext context;

    public AgentTask(String taskId, Long userId, String clientRequestId,
                           AgentTurnContext turn, long createdAt, EventListener eventListener) {
        this.taskId = taskId;
        this.userId = userId;
        this.clientRequestId = clientRequestId;
        this.createdAt = createdAt;
        this.eventListener = eventListener;
        if (turn != null) {
            this.conversationId = turn.getConversationId();
            this.traceId = turn.getTraceId();
            this.userMessageId = turn.getUserMessageId();
            this.assistantMessageId = turn.getAssistantMessageId();
            this.assistantSequenceNo = turn.getAssistantSequenceNo();
            this.title = turn.getTitle();
        }
    }

    public AgentTaskStatus getStatus() {
        return status;
    }

    /**
     * 当前已产生事件的最大 seq（线程安全读）。
     */
    public long getLastSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    // 绑定agent的上下文
    public void bindContext(AgentRuntimeContext ctx) {
        this.context = ctx;
        if (ctx != null) {
            if (ctx.getTraceId() != null) {
                this.traceId = ctx.getTraceId();
            }
            if (ctx.getConversationId() != null) {
                this.conversationId = ctx.getConversationId();
            }
            synchronized (lock) {
                if (status == AgentTaskStatus.CREATED) {
                    status = AgentTaskStatus.RUNNING;
                }
            }
        }
    }

    public void bindConversation(String cid) {
        if (cid != null && !cid.isBlank()) {
            this.conversationId = cid;
        }
    }

    /**
     * 事务 A 完成后回填会话、消息与标题；只在任务第一次绑定或恢复绑定时调用。
     */
    public synchronized void bindTurn(AgentTurnContext turn) {
        if (turn == null) {
            return;
        }
        if (turn.getConversationId() != null) {
            this.conversationId = turn.getConversationId();
        }
        if (turn.getTraceId() != null) {
            this.traceId = turn.getTraceId();
        }
        if (turn.getUserMessageId() != null) {
            this.userMessageId = turn.getUserMessageId();
        }
        if (turn.getAssistantMessageId() != null) {
            this.assistantMessageId = turn.getAssistantMessageId();
        }
        if (turn.getAssistantSequenceNo() > 0) {
            this.assistantSequenceNo = turn.getAssistantSequenceNo();
        }
        if (turn.getTitle() != null) {
            this.title = turn.getTitle();
        }
    }

    public void bindAssistantMessageId(Long messageId) {
        if (messageId != null) {
            this.assistantMessageId = messageId;
        }
    }

    /**
     * 追加一条业务事件。终态之后返回 null。
     */
    public AgentTaskEvent append(AgentRuntimeContext ctx, AgentEventType eventType, Object payload) {
        return appendNamed(ctx, eventType.getEventName(), payload);
    }

    public AgentTaskEvent appendNamed(AgentRuntimeContext ctx, String eventName, Object payload) {
        synchronized (lock) {
            if (status.isTerminal()) {
                return null;
            }
            long next = ++seq;
            AgentTaskEvent event = buildEvent(ctx, eventName, next, payload);
            events.add(event);
            if (status == AgentTaskStatus.CREATED) {
                status = AgentTaskStatus.RUNNING;
            }
            notifyListener(event);
            broadcast(event);
            return event;
        }
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
        synchronized (lock) {
            if (status.isTerminal()) {
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
            notifyListener(done);
            broadcast(done);

            status = terminalStatus;
            this.errorMessage = error;
            this.finishedAt = System.currentTimeMillis();
            if (ctx != null && ctx.getAssistantMessageId() != null) {
                this.assistantMessageId = ctx.getAssistantMessageId();
            }
            closing = new ArrayList<>(subscriptions);
            subscriptions.clear();
        }
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
     * <p>补发与登记订阅在同一把锁里完成，所以不存在“补发完了但新事件漏掉”的窗口。</p>
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
            terminal = status.isTerminal();
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

    public void cancel() {
        this.cancelRequested = true;
        AgentRuntimeContext ctx = this.context;
        if (ctx != null) {
            ctx.setCancelRequested(true);
        }
    }

    public void markReplayDegraded() {
        this.replayDegraded = true;
    }

    // ============ 内部 ============

    private void notifyListener(AgentTaskEvent event) {
        if (eventListener == null) {
            return;
        }
        try {
            eventListener.onEvent(this, event);
        } catch (Exception e) {
            // 归档失败不能打断生成：降级标记让订阅端知道“可能无法完整回放”
            replayDegraded = true;
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
                .traceId(traceId)
                .conversationId(conversationId)
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
            this.conversationId = ctx.getConversationId();
        }
        if (ctx.getTraceId() != null && !ctx.getTraceId().isBlank()) {
            this.traceId = ctx.getTraceId();
        }
        if (ctx.getAssistantMessageId() != null) {
            this.assistantMessageId = ctx.getAssistantMessageId();
        }
    }
}
