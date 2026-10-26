package com.smartorder.edu.agent.runtime;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.config.AgentSseProperties;
import com.smartorder.edu.agent.constant.AgentEventNames;
import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTraceEventDTO;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.model.vo.AgentTaskVO;
import com.smartorder.edu.agent.repository.AgentTaskEventStore;
import com.smartorder.edu.agent.repository.AgentTaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对话任务注册表——本机运行时唯一的任务索引，也是「连接 ↔ 任务」之间唯一的中介。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>预留任务行（MySQL 唯一键幂等）并在事务 A 后绑定会话/消息；</li>
 *   <li>把 {@code TraceEventDispatcher} 的事件写入任务内存日志并推给订阅者，
 *       同时异步镜像到 Redis Stream；</li>
 *   <li>处理订阅请求：同步校验归属/游标 → 本机内存回放或 Redis Stream 回放；</li>
 *   <li>终态收敛：写 done、刷 Stream、落 MySQL 终态；</li>
 *   <li>心跳、僵尸任务与过期清理。</li>
 * </ol>
 *
 * <p><b>单实例执行语义</b>：一个任务只会在创建它的 JVM 中执行。若 MySQL 里状态仍是
 * CREATED/RUNNING，但本机没有运行时对象，说明进程已重启（单机）或请求落到了别的节点
 * （多实例），此时标记 INTERRUPTED 并回放 Redis Stream 已有事件——绝不假装任务还在跑，
 * 也绝不重新执行一次有副作用的业务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskRegistry {

    /** 任务行已落库但本机运行时对象尚未建立时的宽限期；期间订阅返回 503 让客户端重试 */
    private static final long STARTING_GRACE_MS = 30_000L;

    private final AgentTaskRepository repository;

    private final AgentTaskEventStore store;

    private final AgentTaskEventMirror mirror;

    private final AgentSseProperties properties;

    /** 本机正在执行的任务（含刚结束、仍在内存保留期的任务） */
    private final Map<String, AgentTask> live = new ConcurrentHashMap<>();

    /** 已经补发过“中断”事件的任务，避免重复追加 */
    private final Set<String> interrupted = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService housekeeping = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "task-housekeeping");
        thread.setDaemon(true);
        return thread;
    });

    /** 任务行预留结果：created=false 表示命中幂等键，调用方不应再次插入消息。 */
    public record Reservation(String taskId, boolean created) {
    }

    @PostConstruct
    public void start() {
        int heartbeatSeconds = properties.getHeartbeatSeconds();
        if (heartbeatSeconds > 0) {
            housekeeping.scheduleAtFixedRate(this::heartbeatSafely,
                    heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        }
        housekeeping.scheduleAtFixedRate(this::cleanupSafely, 60, 60, TimeUnit.SECONDS);
        log.info("[Task] 注册表已启动: store={}, heartbeat={}s",
                properties.getEventStore(), heartbeatSeconds);
    }

    @PreDestroy
    public void shutdown() {
        housekeeping.shutdownNow();
    }

    // ============ 创建与绑定 ============

    /**
     * 预留任务行。
     *
     * <p>此时还没有插消息；先抢 MySQL 唯一键，成功后才做事务 A。这样两个并发重试
     * 不会各自插入一对 user/assistant 消息。</p>
     */
    public Reservation reserve(Long userId, String preferredTaskId, String clientRequestId,
                               String conversationId) {
        if (userId == null) {
            throw AgentTaskException.forbidden();
        }
        long now = System.currentTimeMillis();
        AgentTaskMeta candidate = AgentTaskMeta.builder()
                .taskId(preferredTaskId)
                .conversationId(conversationId)
                .userId(userId)
                .clientRequestId(clientRequestId)
                .status(AgentTaskStatus.CREATED.name())
                .createdAt(now)
                .updatedAt(now)
                .lastSeq(0L)
                .replayable(true)
                .build();
        AgentTaskRepository.Reservation reservation = repository.reserve(candidate);
        return new Reservation(reservation.meta().getTaskId(), reservation.created());
    }

    /**
     * 事务 A 完成后绑定会话/消息，并创建本机运行时对象。
     */
    public AgentTask bindTurn(String taskId, Long userId, String clientRequestId, AgentTurnContext turn) {
        AgentTask task = live.get(taskId);
        if (task == null) {
            AgentTask created = new AgentTask(taskId, userId, clientRequestId, turn,
                    System.currentTimeMillis(),
                    (gen, event) -> mirror.append(gen.getTaskId(), event));
            AgentTask existing = live.putIfAbsent(taskId, created);
            task = existing != null ? existing : created;
        }
        task.bindTurn(turn);
        repository.bindTurn(taskId, turn.getConversationId(), turn.getTraceId(),
                turn.getUserMessageId(), turn.getAssistantMessageId(),
                turn.getAssistantSequenceNo(), turn.getTitle());
        log.info("[Task] 绑定本轮消息: taskId={}, userId={}, cid={}, userMsgId={}, assistantMsgId={}, clientRequestId={}",
                taskId, userId, turn.getConversationId(),
                turn.getUserMessageId(), turn.getAssistantMessageId(), clientRequestId);
        return task;
    }

    /**
     * 事务 A 失败：删除还没有绑定消息的占位任务，让客户端可以重试。
     */
    public void discardReservation(String taskId) {
        live.remove(taskId);
        try {
            repository.discardUnbound(taskId);
        } catch (Exception e) {
            log.warn("[Task] 清理占位任务失败: taskId={}, reason={}", taskId, e.getMessage());
        }
    }

    public AgentTask getLive(String taskId) {
        return taskId == null ? null : live.get(taskId);
    }

    public void bindContext(String taskId, AgentRuntimeContext context) {
        AgentTask task = getLive(taskId);
        if (task != null && context != null) {
            task.bindContext(context);
            try {
                repository.markRunning(taskId);
            } catch (Exception e) {
                log.warn("[Task] 运行态回写失败: taskId={}, reason={}", taskId, e.getMessage());
            }
        }
    }

    /**
     * 会话 ID 在 openReply 之后才确定，绑定后刷新恢复才能找到这次任务。
     */
    public void bindConversation(String taskId, String conversationId) {
        if (taskId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        AgentTask task = getLive(taskId);
        if (task != null) {
            task.bindConversation(conversationId);
        }
    }

    public String findTaskIdByClientRequest(Long userId, String clientRequestId) {
        AgentTaskMeta meta = repository.findByClientRequest(userId, clientRequestId);
        return meta == null ? null : meta.getTaskId();
    }

    // ============ 事件发布 ============

    /**
     * 发布业务事件。返回 null 表示“没有任务上下文”或“任务已终态”。
     */
    public AgentTaskEvent publish(AgentRuntimeContext context, AgentEventType eventType, Object payload) {
        AgentTask task = liveOf(context);
        if (task == null) {
            return null;
        }
        return task.append(context, eventType, payload);
    }

    /**
     * 发布只走在线连接、不进入回放日志的即时事件。
     */
    public void publishEphemeral(AgentRuntimeContext context, String eventName, Object payload) {
        AgentTask task = liveOf(context);
        if (task != null) {
            task.publishEphemeral(context, eventName, payload);
        }
    }

    /**
     * 结束任务：写 done 事件 → 刷 Redis Stream → 落 MySQL 终态。重复调用安全（终态只发一次）。
     */
    public void finish(AgentRuntimeContext context, Map<String, Object> donePayload,
                       AgentTaskStatus terminalStatus, String errorMessage) {
        AgentTask task = liveOf(context);
        if (task == null) {
            return;
        }
        if (task.finish(context, donePayload, terminalStatus, errorMessage) == null) {
            return;
        }
        mirror.flush(task.getTaskId());
        persistTerminalState(task, terminalStatus, errorMessage);
    }

    /**
     * 编排线程意外退出时的兜底收敛（编排器自身会捕获所有异常，这里是最后一道保险）。
     */
    public void fail(String taskId, String errorMessage) {
        AgentTask task = getLive(taskId);
        if (task == null || task.getStatus().isTerminal()) {
            return;
        }
        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("message", errorMessage);
        task.appendNamed(null, AgentEventNames.ERROR, errorPayload);

        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("conversationId", task.getConversationId());
        donePayload.put("traceId", task.getTraceId());
        donePayload.put("error", errorMessage);
        if (task.finish(null, donePayload, AgentTaskStatus.FAILED, errorMessage) == null) {
            // 另一个线程已经收敛为终态，不能再把状态覆盖成 FAILED
            return;
        }

        mirror.flush(taskId);
        persistTerminalState(task, AgentTaskStatus.FAILED, errorMessage);
    }

    public boolean isCancelRequested(AgentRuntimeContext context) {
        AgentTask task = liveOf(context);
        return task != null && task.isCancelRequested();
    }

    // ============ 订阅与回放 ============

    /**
     * 同步校验订阅请求：归属、游标、可回放性。只读 MySQL/Redis，不向连接写任何字节。
     *
     * <p>必须在控制器返回 SseEmitter 之前调用：SSE 响应一旦开始，403/404/410
     * 就无法再作为 HTTP 状态码返回，只能退化成连接错误。</p>
     *
     * @return 校验通过后的接入计划，交给 {@link #attach(AgentTaskSubscriptionPlan)} 执行
     */
    public AgentTaskSubscriptionPlan validateSubscription(String taskId, Long userId,
                                                          long afterSeq, AgentEventSink sink) {
        if (userId == null) {
            throw AgentTaskException.forbidden();
        }
        if (afterSeq < 0) {
            throw AgentTaskException.invalidCursor(afterSeq);
        }

        // 1. 本机正在执行：内存日志权威，校验只读 lastSeq
        AgentTask task = live.get(taskId);
        if (task != null) {
            requireOwner(task.getUserId(), userId);
            if (afterSeq > task.getLastSeq()) {
                throw AgentTaskException.invalidCursor(afterSeq);
            }
            return AgentTaskSubscriptionPlan.live(task,
                    new AgentTaskSubscription(taskId, afterSeq, sink));
        }

        // 2. 本机没有运行时对象：MySQL 元数据 + Redis Stream 尾部
        AgentTaskMeta meta = loadMetaOrThrow(taskId);
        requireOwner(meta.getUserId(), userId);
        // 另一个请求刚刚预留了任务行、还没走到 bindTurn：不要把它误标成中断，
        // 返回 503 让客户端按退避策略重试；宽限期过后才按“执行者消失”收敛。
        if (isStarting(meta)) {
            throw AgentTaskException.starting(taskId);
        }
        long lastSeq = Math.max(meta.getLastSeq(), lastEventSeqOrThrow(taskId));
        if (afterSeq > lastSeq) {
            throw AgentTaskException.invalidCursor(afterSeq);
        }
        if (!meta.isReplayable() && afterSeq < lastSeq) {
            throw AgentTaskException.cursorExpired(afterSeq);
        }
        // 归档缺口也属于游标过期。只读第一条事件，避免把整个 backlog 拉进请求线程。
        if (afterSeq < lastSeq) {
            AgentTaskEvent next = readFirstAfterOrThrow(taskId, afterSeq);
            if (next != null && next.getSeq() > afterSeq + 1) {
                throw AgentTaskException.cursorExpired(afterSeq);
            }
        }
        return AgentTaskSubscriptionPlan.archived(meta,
                new AgentTaskSubscription(taskId, afterSeq, sink));
    }

    /**
     * 真正接入订阅：回放积压并登记实时推送。
     *
     * <p>可能被慢连接背压，必须跑在任务线程池上；本机任务在回放期间仍持有 task 锁，
     * 但 servlet 线程已经返回，浏览器可以立即收到首字节。</p>
     */
    public void attach(AgentTaskSubscriptionPlan plan) {
        if (plan.isLive()) {
            plan.liveTask().attach(plan.subscription());
            return;
        }
        attachArchive(plan.archivedMeta(), plan.subscription());
    }

    public AgentTaskVO status(String taskId, Long userId) {
        if (userId == null) {
            throw AgentTaskException.forbidden();
        }
        AgentTask task = live.get(taskId);
        if (task != null) {
            requireOwner(task.getUserId(), userId);
            return toVO(task);
        }
        AgentTaskMeta meta = loadMetaOrThrow(taskId);
        requireOwner(meta.getUserId(), userId);
        // 运行中的 MySQL 元数据不随每条事件更新 lastSeq；以 Redis Stream 尾部为准，
        // 否则前端做“游标超前”对账会拿到过期的 0。归档暂时不可读时退回元数据。
        long lastSeq = meta.getLastSeq();
        try {
            lastSeq = Math.max(lastSeq, store.lastEventSeq(taskId));
        } catch (Exception e) {
            log.warn("[Task] 状态查询读取 Stream 尾部失败，使用元数据 lastSeq: taskId={}, reason={}",
                    taskId, e.getMessage());
        }
        return toVO(meta, lastSeq);
    }

    /**
     * 会话最新任务，用于刷新页面后恢复。
     *
     * @return 不存在或不属于当前用户时返回 null
     */
    public AgentTaskVO activeTask(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank() || userId == null) {
            return null;
        }
        AgentTaskMeta meta = repository.findLatestByConversation(conversationId);
        if (meta == null) {
            return null;
        }
        try {
            return status(meta.getTaskId(), userId);
        } catch (AgentTaskException e) {
            log.debug("[Task] 会话任务不可用: cid={}, taskId={}, reason={}",
                    conversationId, meta.getTaskId(), e.getMessage());
            return null;
        }
    }

    /**
     * 请求取消。取消是协作式的：引擎在循环边界检查标记，已提交的副作用不自动回滚。
     *
     * @return true 表示已把取消标记送达正在执行的任务
     */
    public boolean cancel(String taskId, Long userId) {
        if (userId == null) {
            throw AgentTaskException.forbidden();
        }
        AgentTask task = live.get(taskId);
        if (task == null) {
            AgentTaskMeta meta = loadMetaOrThrow(taskId);
            requireOwner(meta.getUserId(), userId);
            return false;
        }
        requireOwner(task.getUserId(), userId);
        if (task.getStatus().isTerminal()) {
            return false;
        }
        task.cancel();
        log.info("[Task] 收到取消请求: taskId={}, userId={}", taskId, userId);
        return true;
    }

    // ============ 内部 ============

    private AgentTask liveOf(AgentRuntimeContext context) {
        if (context == null || context.getTaskId() == null) {
            return null;
        }
        return live.get(context.getTaskId());
    }

    private void persistTerminalState(AgentTask task, AgentTaskStatus status, String error) {
        try {
            repository.updateTerminalState(task.getTaskId(), status, task.getLastSeq(),
                    error, task.getAssistantMessageId(), !task.isReplayDegraded());
        } catch (Exception e) {
            log.error("[Task] 终态写入失败: taskId={}, status={}",
                    task.getTaskId(), status, e);
        }
    }

    private void attachArchive(AgentTaskMeta validatedMeta, AgentTaskSubscription subscription) {
        try {
            // 校验与接入之间隔了一次线程调度：重新读元数据，
            // 避免把刚刚完成的任务误标成中断。
            AgentTaskMeta meta = repository.find(validatedMeta.getTaskId());
            if (meta == null) {
                throw AgentTaskException.notFound(validatedMeta.getTaskId());
            }
            if (isStarting(meta)) {
                // 校验与 attach 之间任务可能仍在启动，不能让本次连接等一个不存在的实时尾。
                throw AgentTaskException.starting(meta.getTaskId());
            }
            if (!meta.statusEnum().isTerminal()) {
                meta = interruptStale(meta);
            }
            replayFromArchive(meta, subscription);
        } catch (AgentTaskException e) {
            log.info("[Task] 归档回放被拒绝: taskId={}, status={}, reason={}",
                    subscription.getTaskId(), e.getStatus().value(), e.getMessage());
            subscription.completeWithError(e);
        } catch (Exception e) {
            log.error("[Task] 归档回放失败: taskId={}", subscription.getTaskId(), e);
            subscription.completeWithError(storeUnavailable(e));
        }
    }

    private AgentTaskEvent readFirstAfterOrThrow(String taskId, long afterSeq) {
        try {
            return store.readFirstAfter(taskId, afterSeq);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
    }

    private long lastEventSeqOrThrow(String taskId) {
        try {
            return store.lastEventSeq(taskId);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
    }

    private void replayFromArchive(AgentTaskMeta meta, AgentTaskSubscription subscription) {
        // 先把心跳写出去，让浏览器立刻拿到首字节；随后才是可能较慢的 Stream 读取。
        if (!subscription.sendHeartbeat()) {
            subscription.complete();
            return;
        }
        long afterSeq = subscription.getAfterSeq();
        List<AgentTaskEvent> backlog;
        try {
            backlog = store.readAfter(meta.getTaskId(), afterSeq);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
        // 归档不完整：绝不静默跳到最新事件，让客户端明确知道无法保证完整回放
        if (!backlog.isEmpty() && backlog.get(0).getSeq() > afterSeq + 1) {
            throw AgentTaskException.cursorExpired(afterSeq);
        }
        if (!meta.isReplayable() && afterSeq < meta.getLastSeq()) {
            throw AgentTaskException.cursorExpired(afterSeq);
        }
        // 元数据说还有事件，但 Redis Stream 已经不在了（过期/被清理）
        if (backlog.isEmpty() && meta.getLastSeq() > 0 && afterSeq < meta.getLastSeq()) {
            throw AgentTaskException.cursorExpired(afterSeq);
        }
        for (AgentTaskEvent event : backlog) {
            if (!subscription.deliver(event)) {
                log.info("[Task] 归档回放中断: taskId={}, 已补发到 seq={}",
                        meta.getTaskId(), subscription.getLastSentSeq());
                subscription.complete();
                return;
            }
        }
        subscription.complete();
    }

    /**
     * 进程重启/请求落到别的节点：MySQL 里还停在 CREATED/RUNNING，但本机没有执行者。
     * 补发一条 error + done 到 Redis Stream，把状态收敛为 INTERRUPTED，
     * 让客户端明确失败而不是永久加载。
     */
    private AgentTaskMeta interruptStale(AgentTaskMeta meta) {
        String taskId = meta.getTaskId();
        if (!interrupted.add(taskId)) {
            AgentTaskMeta latest = repository.find(taskId);
            return latest != null ? latest : meta;
        }
        try {
            // 异步 attach 与任务线程可能并发：追加前再确认一次，
            // 避免把刚刚完成的任务标成中断，或与 done 事件撞 seq。
            AgentTaskMeta latest = repository.find(taskId);
            if (latest != null && latest.statusEnum().isTerminal()) {
                return latest;
            }
            if (latest != null) {
                meta = latest;
            }

            // 以 Redis Stream 尾部为准分配中断事件的 seq：运行中的 MySQL lastSeq 可能还是 0，
            // 直接 +1 会与已有事件撞号，客户端会把它当成重复游标丢掉。
            long errorSeq = Math.max(meta.getLastSeq(), lastEventSeqOrThrow(taskId)) + 1;
            String message = "任务已中断：服务端已不再持有该任务，只能回放已产生的内容。";
            store.appendEvent(taskId, syntheticEvent(meta, AgentEventNames.ERROR, errorSeq,
                    Map.of("message", message)));
            long doneSeq = errorSeq + 1;
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("conversationId", meta.getConversationId());
            donePayload.put("taskId", taskId);
            donePayload.put("status", AgentTaskStatus.INTERRUPTED.name());
            donePayload.put("error", message);
            // done 与在线链路保持一致：扁平结构，不套事件信封
            store.appendEvent(taskId, new AgentTaskEvent(doneSeq, AgentEventNames.DONE,
                    JSON.toJSONString(donePayload), System.currentTimeMillis()));
            repository.updateTerminalState(taskId, AgentTaskStatus.INTERRUPTED, doneSeq,
                    message, meta.getAssistantMessageId(), meta.isReplayable());
            log.warn("[Task] 标记中断: taskId={}, lastSeq={}", taskId, doneSeq);
            AgentTaskMeta updated = repository.find(taskId);
            return updated != null ? updated : meta;
        } catch (Exception e) {
            interrupted.remove(taskId);
            log.error("[Task] 中断标记失败: taskId={}", taskId, e);
            throw storeUnavailable(e);
        }
    }

    private AgentTaskEvent syntheticEvent(AgentTaskMeta meta, String eventName,
                                          long seq, Object payload) {
        AgentTraceEventDTO dto = AgentTraceEventDTO.builder()
                .taskId(meta.getTaskId())
                .traceId(meta.getTraceId())
                .conversationId(meta.getConversationId())
                .userId(meta.getUserId())
                .eventType(eventName)
                .seq((int) seq)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
        return new AgentTaskEvent(seq, eventName, JSON.toJSONString(dto), dto.getTimestamp());
    }

    private AgentTaskMeta loadMetaOrThrow(String taskId) {
        AgentTaskMeta meta;
        try {
            meta = repository.find(taskId);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
        if (meta == null) {
            throw AgentTaskException.notFound(taskId);
        }
        return meta;
    }

    private void requireOwner(Long ownerUserId, Long userId) {
        if (ownerUserId == null || !Objects.equals(ownerUserId, userId)) {
            throw AgentTaskException.forbidden();
        }
    }

    private boolean isStarting(AgentTaskMeta meta) {
        // 只有“任务行已预留、消息还没绑定”的窗口需要宽限；
        // 已绑定 assistant 消息却不在本机 live 里，说明进程已重启或请求落到了别的节点。
        return !meta.statusEnum().isTerminal()
                && meta.getAssistantMessageId() == null
                && System.currentTimeMillis() - meta.getUpdatedAt() < STARTING_GRACE_MS;
    }

    private AgentTaskException storeUnavailable(Exception cause) {
        log.error("[Task] 任务存储不可用", cause);
        return new AgentTaskException(HttpStatus.SERVICE_UNAVAILABLE, "STORE_UNAVAILABLE",
                "任务事件归档暂不可用，请稍后重试");
    }

    private void heartbeatSafely() {
        try {
            for (AgentTask task : live.values()) {
                if (!task.getStatus().isTerminal()) {
                    task.heartbeat();
                }
            }
        } catch (Exception e) {
            log.warn("[Task] 心跳任务异常: {}", e.getMessage());
        }
    }

    private void cleanupSafely() {
        try {
            long retentionMs = Math.max(1, properties.getMemoryRetentionMinutes()) * 60_000L;
            long cutoff = System.currentTimeMillis() - retentionMs;
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, AgentTask> entry : live.entrySet()) {
                AgentTask task = entry.getValue();
                if (task.getStatus().isTerminal() && task.getFinishedAt() > 0
                        && task.getFinishedAt() < cutoff) {
                    expired.add(entry.getKey());
                }
            }
            for (String taskId : expired) {
                live.remove(taskId);
                interrupted.remove(taskId);
            }
            // 进程在 openReply 之前退出的占位任务：没有消息可恢复，直接删除让重试重新建任务。
            for (AgentTaskMeta stale : repository.findStaleReservations()) {
                if (!live.containsKey(stale.getTaskId())) {
                    repository.discardUnbound(stale.getTaskId());
                    log.warn("[Task] 清理未开始的占位任务: taskId={}, userId={}",
                            stale.getTaskId(), stale.getUserId());
                }
            }
        } catch (Exception e) {
            log.warn("[Task] 清理任务异常: {}", e.getMessage());
        }
    }

    private AgentTaskVO toVO(AgentTask task) {
        return AgentTaskVO.builder()
                .taskId(task.getTaskId())
                .conversationId(task.getConversationId())
                .traceId(task.getTraceId())
                .userMessageId(task.getUserMessageId())
                .assistantMessageId(task.getAssistantMessageId())
                .assistantSequenceNo(task.getAssistantSequenceNo())
                .title(task.getTitle())
                .status(task.getStatus().name())
                .lastSeq(task.getLastSeq())
                .errorMessage(task.getErrorMessage())
                .replayable(!task.isReplayDegraded())
                .createdAt(task.getCreatedAt())
                .build();
    }

    private AgentTaskVO toVO(AgentTaskMeta meta, long lastSeq) {
        return AgentTaskVO.builder()
                .taskId(meta.getTaskId())
                .conversationId(meta.getConversationId())
                .traceId(meta.getTraceId())
                .userMessageId(meta.getUserMessageId())
                .assistantMessageId(meta.getAssistantMessageId())
                .assistantSequenceNo(meta.getAssistantSequenceNo())
                .title(meta.getTitle())
                .status(meta.statusEnum().name())
                .lastSeq(lastSeq)
                .errorMessage(meta.getErrorMessage())
                .replayable(meta.isReplayable())
                .createdAt(meta.getCreatedAt())
                .build();
    }
}
