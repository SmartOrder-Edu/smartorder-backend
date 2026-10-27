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
import com.smartorder.edu.agent.runtime.store.AgentTaskEventStore;
import com.smartorder.edu.agent.runtime.store.AgentTaskStateStore;
import com.smartorder.edu.agent.service.AgentTaskMetaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对话任务注册表——「连接 ↔ 任务」之间的中介。
 *
 * <p><b>状态存哪里</b>（这次改造的核心）：</p>
 * <ol>
 *   <li><b>Redis</b>（{@link AgentTaskStateStore}）：任务状态、seq 水位、会话/消息绑定、
 *       取消标记、中断标记、"正在执行"索引、执行者节点租约。<b>权威</b>，跨进程可见；</li>
 *   <li><b>MySQL</b>（{@link AgentTaskMetaService}）：落库副本——存在性、归属、终态、审计。重启后仍在；</li>
 *   <li><b>本机内存</b>（{@code executions}）：只放 {@link AgentTask} 句柄，
 *       里面是不可序列化的 SSE 订阅者与内存回放日志。<b>不是状态源</b>，
 *       丢了只影响本机连接的实时推送，不影响任何查询/取消/中断判定。</li>
 * </ol>
 *
 * <p>所以本类不再需要"本机正在跑哪些任务"和"哪些任务已经补发过中断"这两份 Map/Set：
 * 前者由 Redis 的 active 索引回答，后者由 Redis 的 SETNX 标记回答。</p>
 *
 * <p><b>执行语义</b>：一个任务仍然只会在一个 JVM 里执行（单写者，seq 才能本地自增）。
 * 与以前不同的是，现在"这个任务在别处活着"是可判定的：任务状态里记了执行者 nodeId，
 * 节点租约靠 TTL 自动过期。于是——</p>
 * <ul>
 *   <li>执行者进程崩溃 → 租约过期 → 其它节点可以判定中断并收敛；</li>
 *   <li>执行者还活着只是不在本机 → 订阅请求返回 503，绝不在这里伪造中断事件
 *       （旧实现会往别人的 Stream 里硬塞 error+done，把还在跑的任务写死）；</li>
 *   <li>取消请求打到任意节点都能生效（标记进 Redis，执行节点 1 秒内轮询到）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskRegistry {

    /** 任务行已落库但本机运行时对象尚未建立时的宽限期；期间订阅返回 503 让客户端重试 */
    private static final long STARTING_GRACE_MS = 30_000L;

    /** 跨节点取消信号的巡检间隔（秒） */
    private static final long CANCEL_POLL_SECONDS = 1L;

    private final AgentTaskMetaService taskMetaService;

    private final AgentTaskEventStore store;

    private final AgentTaskStateStore stateStore;

    private final AgentTaskEventMirror mirror;

    private final AgentSseProperties properties;

    /**
     * 本机执行句柄——<b>不是</b>任务索引。
     *
     * <p>只承载 {@code SseEmitter} 与内存事件日志（都不可序列化，物理上搬不进 Redis）。
     * 任务是否在跑、被谁跑、是否已取消，一律以 {@link #stateStore} 为准。</p>
     */
    private final Map<String, AgentTask> executions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService housekeeping = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "task-housekeeping");
        thread.setDaemon(true);
        return thread;
    });

    /** 任务行预留结果：created=false 表示命中幂等键，调用方不应再次插入消息。 */
    public record Reservation(String taskId, boolean created, String draftId) {
    }

    @PostConstruct
    public void start() {
        int heartbeatSeconds = properties.getHeartbeatSeconds();
        if (heartbeatSeconds > 0) {
            housekeeping.scheduleAtFixedRate(this::heartbeatSafely,
                    heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        }
        // 节点租约与索引裁剪必须独立于 SSE 心跳：关掉心跳不能让本节点被判定为"已死亡"
        long leaseRefresh = Math.max(10L, heartbeatSeconds > 0 ? heartbeatSeconds : 15L);
        housekeeping.scheduleAtFixedRate(this::leaseSafely,
                leaseRefresh, leaseRefresh, TimeUnit.SECONDS);
        // 取消是协作式的：别的节点置位后，本机靠这条巡检把标记推进执行上下文
        housekeeping.scheduleAtFixedRate(this::pollCancelSafely,
                CANCEL_POLL_SECONDS, CANCEL_POLL_SECONDS, TimeUnit.SECONDS);
        housekeeping.scheduleAtFixedRate(this::cleanupSafely, 60, 60, TimeUnit.SECONDS);
        log.info("[Task] 注册表已启动: store={}, stateStore={}, nodeId={}, heartbeat={}s",
                properties.getEventStore(), stateStore.getClass().getSimpleName(),
                stateStore.nodeId(), heartbeatSeconds);
    }

    @PreDestroy
    public void shutdown() {
        housekeeping.shutdownNow();
        // 优雅停机主动放弃执行者身份：别的节点不必等租约自然过期（默认 45s）就能收敛中断
        try {
            stateStore.releaseNodeLease();
        } catch (Exception e) {
            log.warn("[Task] 释放节点租约失败: {}", e.getMessage());
        }
    }

    // ============ 创建与绑定 ============

    /**
     * 预留任务行。
     *
     * <p>此时还没有插消息；先抢 MySQL 唯一键，成功后才做事务 A。这样两个并发重试
     * 不会各自插入一对 user/assistant 消息。抢到之后顺带在 Redis 建一份运行时状态，
     * 让"任务已预留但还没绑定消息"的启动窗口对其他节点可见。</p>
     */
    public Reservation reserve(Long userId, String preferredTaskId, String taskRequestId,
                               String conversationId, String draftId) {
        if (userId == null) {
            throw AgentTaskException.forbidden();
        }
        long now = System.currentTimeMillis();
        AgentTaskMeta candidate = AgentTaskMeta.builder()
                .taskId(preferredTaskId)
                .conversationId(conversationId)
                .draftId(draftId)
                .userId(userId)
                .taskRequestId(taskRequestId)
                .status(AgentTaskStatus.CREATED.name())
                .createdAt(now)
                .updatedAt(now)
                .lastSeq(0L)
                .replayable(true)
                .build();
        AgentTaskMetaService.Reservation reservation = taskMetaService.reserve(candidate);
        if (reservation.created()) {
            seedState(reservation.meta(), now);
        }
        return new Reservation(reservation.meta().getTaskId(), reservation.created(),
                reservation.meta().getDraftId());
    }

    /**
     * 事务 A 完成后绑定会话/消息，并创建本机执行句柄。
     *
     * <p>这里不再往 {@code ConcurrentHashMap} 里"注册任务"：任务状态由
     * {@link AgentTask#bindTurn} 写通 Redis，本机只拿一个投递句柄。</p>
     */
    public AgentTask bindTurn(String taskId, Long userId, String taskRequestId, AgentTurnContext turn) {
        AgentTask task = execution(taskId, userId, taskRequestId);
        task.bindTurn(turn);
        taskMetaService.bindTurn(taskId, turn.getConversationId(), turn.getTraceId(),
                turn.getUserMessageId(), turn.getAssistantMessageId(),
                turn.getAssistantSequenceNo(), turn.getTitle());
        log.info("[Task] 绑定本轮消息: taskId={}, userId={}, cid={}, userMsgId={}, assistantMsgId={}, taskRequestId={}",
                taskId, userId, turn.getConversationId(),
                turn.getUserMessageId(), turn.getAssistantMessageId(), taskRequestId);
        return task;
    }

    /**
     * 事务 A 失败：删除还没有绑定消息的占位任务，让客户端可以重试。
     */
    public void discardReservation(String taskId) {
        executions.remove(taskId);
        try {
            stateStore.remove(taskId);
        } catch (Exception e) {
            log.warn("[Task] 清理占位任务运行时状态失败: taskId={}, reason={}", taskId, e.getMessage());
        }
        try {
            taskMetaService.discardUnbound(taskId);
        } catch (Exception e) {
            log.warn("[Task] 清理占位任务失败: taskId={}, reason={}", taskId, e.getMessage());
        }
    }

    /**
     * 老任务行没有 draft_id 时补写（升级后第一次带 draft 的重试）。
     */
    public void bindDraftId(String taskId, String draftId) {
        if (taskId == null || draftId == null || draftId.isBlank()) {
            return;
        }
        try {
            taskMetaService.bindDraftId(taskId, draftId);
        } catch (Exception e) {
            log.warn("[Task] 回写 draft_id 失败: taskId={}, reason={}", taskId, e.getMessage());
        }
    }

    /**
     * 任务是否还在执行（可能是本机，也可能是别的节点）。
     *
     * <p>判据全部来自 Redis：状态非终态 + 执行者节点租约未过期。
     * 旧实现只看本机 Map，于是"别的节点在跑"会被误判成"执行者已消失"，
     * 中断恢复逻辑就会把用户已经看到的回答改写成中断态。</p>
     */
    public boolean isExecuting(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        AgentTask local = executions.get(taskId);
        if (local != null && !local.isTerminal()) {
            return true;
        }
        AgentTaskState state = loadStateQuietly(taskId);
        if (state == null || state.isTerminal()) {
            return false;
        }
        return isNodeAliveQuietly(state.getNodeId());
    }

    public void bindContext(String taskId, AgentRuntimeContext context) {
        AgentTask task = taskId == null ? null : executions.get(taskId);
        if (task != null && context != null) {
            task.bindContext(context);
            try {
                taskMetaService.markRunning(taskId);
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
        AgentTask task = executions.get(taskId);
        if (task != null) {
            task.bindConversation(conversationId);
        }
    }

    public String findTaskIdByTaskRequest(Long userId, String taskRequestId) {
        AgentTaskMeta meta = taskMetaService.findByTaskRequest(userId, taskRequestId);
        return meta == null ? null : meta.getTaskId();
    }

    // ============ 事件发布 ============

    /**
     * 发布业务事件。返回 null 表示"没有任务上下文"或"任务已终态"。
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
     * 结束任务：写 done 事件 → 刷 Redis Stream → 落 Redis 状态 + MySQL 终态。重复调用安全（终态只发一次）。
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
        AgentTask task = taskId == null ? null : executions.get(taskId);
        if (task == null || task.isTerminal()) {
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
     * 同步校验订阅请求：归属、游标、可回放性。只读 Redis/MySQL，不向连接写任何字节。
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
        AgentTask task = taskId == null ? null : executions.get(taskId);
        if (task != null) {
            requireOwner(task.getUserId(), userId);
            if (afterSeq > task.getLastSeq()) {
                throw AgentTaskException.invalidCursor(afterSeq);
            }
            return AgentTaskSubscriptionPlan.live(task,
                    new AgentTaskSubscription(taskId, afterSeq, sink));
        }

        // 2. 本机没有执行句柄：Redis 运行时状态（缺失时回源 MySQL） + Redis Stream 尾部
        AgentTaskMeta meta = loadMetaOrThrow(taskId);
        requireOwner(meta.getUserId(), userId);
        // 任务在别的节点上活着：这里既不能接管，更不能伪造中断——让客户端换节点重试
        assertExecutorHere(taskId, meta);
        // 另一个请求刚刚预留了任务行、还没走到 bindTurn：不要把它误标成中断，
        // 返回 503 让客户端按退避策略重试；宽限期过后才按"执行者消失"收敛。
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
        AgentTask task = taskId == null ? null : executions.get(taskId);
        if (task != null) {
            requireOwner(task.getUserId(), userId);
            return toVO(task);
        }
        AgentTaskMeta meta = loadMetaOrThrow(taskId);
        requireOwner(meta.getUserId(), userId);
        // 运行中的状态不会随每条事件刷新 lastSeq；以 Redis Stream 尾部为准，
        // 否则前端做"游标超前"对账会拿到过期的 0。归档暂时不可读时退回状态里的水位。
        long lastSeq = meta.getLastSeq();
        try {
            lastSeq = Math.max(lastSeq, store.lastEventSeq(taskId));
        } catch (Exception e) {
            log.warn("[Task] 状态查询读取 Stream 尾部失败，使用状态水位: taskId={}, reason={}",
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
        String taskId = null;
        try {
            taskId = stateStore.latestTaskId(conversationId);
        } catch (Exception e) {
            log.debug("[Task] 会话任务索引读取失败，回源 MySQL: cid={}, reason={}",
                    conversationId, e.getMessage());
        }
        if (taskId == null) {
            AgentTaskMeta meta = taskMetaService.findLatestByConversation(conversationId);
            taskId = meta == null ? null : meta.getTaskId();
        }
        if (taskId == null) {
            return null;
        }
        try {
            return status(taskId, userId);
        } catch (AgentTaskException e) {
            log.debug("[Task] 会话任务不可用: cid={}, taskId={}, reason={}",
                    conversationId, taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 请求取消。取消是协作式的：引擎在循环边界检查标记，已提交的副作用不自动回滚。
     *
     * <p>与旧实现的关键差别：任务不在本机也能取消。标记写进 Redis，
     * 执行节点最多 1 秒内轮询到并停止生成——不再要求"取消请求必须打到同一个 JVM"。</p>
     *
     * @return true 表示取消标记已送达某个仍在执行的节点
     */
    public boolean cancel(String taskId, Long userId) {
        if (userId == null) {
            throw AgentTaskException.forbidden();
        }
        AgentTask task = taskId == null ? null : executions.get(taskId);
        if (task != null) {
            requireOwner(task.getUserId(), userId);
            if (task.isTerminal()) {
                return false;
            }
            task.cancel();
            log.info("[Task] 收到取消请求（本机执行）: taskId={}, userId={}", taskId, userId);
            return true;
        }

        // 不在本机：状态还在且执行者节点活着，说明标记会被执行节点消费
        AgentTaskState state = loadStateQuietly(taskId);
        if (state == null) {
            AgentTaskMeta meta = loadMetaOrThrow(taskId);
            requireOwner(meta.getUserId(), userId);
            return false;
        }
        requireOwner(state.getUserId(), userId);
        if (state.isTerminal() || !isNodeAliveQuietly(state.getNodeId())) {
            return false;
        }
        try {
            stateStore.requestCancel(taskId);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
        log.info("[Task] 收到取消请求（转发到节点）: taskId={}, userId={}, nodeId={}",
                taskId, userId, state.getNodeId());
        return true;
    }

    // ============ 内部 ============

    private AgentTask liveOf(AgentRuntimeContext context) {
        if (context == null || context.getTaskId() == null) {
            return null;
        }
        return executions.get(context.getTaskId());
    }

    /**
     * 取得（或建立）本机执行句柄。状态从 Redis 读；读不到说明状态已过期或从未写过，
     * 此时用最小信息兜底，绝不假设"Redis 没有 = 任务不存在"（存在性由 MySQL 判定）。
     */
    private AgentTask execution(String taskId, Long userId, String taskRequestId) {
        return executions.computeIfAbsent(taskId, id -> {
            AgentTaskState state = loadStateQuietly(id);
            if (state == null) {
                long now = System.currentTimeMillis();
                state = AgentTaskState.builder()
                        .taskId(id)
                        .userId(userId)
                        .taskRequestId(taskRequestId)
                        .status(AgentTaskStatus.CREATED.name())
                        .createdAt(now)
                        .updatedAt(now)
                        .lastSeq(0L)
                        .replayable(true)
                        .nodeId(stateStore.nodeId())
                        .build();
            } else if (state.getNodeId() == null) {
                // 状态由旧版本写入或曾被清理：本机接管执行，补上执行者标记
                state.setNodeId(stateStore.nodeId());
            }
            return new AgentTask(state, stateStore,
                    (task, event) -> mirror.append(task.getTaskId(), event));
        });
    }

    /**
     * 预留成功后写入运行时状态的初始快照。
     *
     * <p>失败只告警不阻断：MySQL 是落库权威，Redis 状态缺失时查询会自动回源。</p>
     */
    private void seedState(AgentTaskMeta meta, long now) {
        try {
            stateStore.save(AgentTaskState.builder()
                    .taskId(meta.getTaskId())
                    .userId(meta.getUserId())
                    .taskRequestId(meta.getTaskRequestId())
                    .conversationId(meta.getConversationId())
                    .status(AgentTaskStatus.CREATED.name())
                    .createdAt(now)
                    .updatedAt(now)
                    .lastSeq(0L)
                    .replayable(true)
                    .nodeId(stateStore.nodeId())
                    .build());
        } catch (Exception e) {
            log.warn("[Task] 初始化运行时状态失败（回源 MySQL 兜底）: taskId={}, reason={}",
                    meta.getTaskId(), e.getMessage());
        }
    }

    private void persistTerminalState(AgentTask task, AgentTaskStatus status, String error) {
        try {
            taskMetaService.updateTerminalState(task.getTaskId(), status, task.getLastSeq(),
                    error, task.getAssistantMessageId(), !task.isReplayDegraded());
        } catch (Exception e) {
            log.error("[Task] 终态写入失败: taskId={}, status={}",
                    task.getTaskId(), status, e);
        }
    }

    private void attachArchive(AgentTaskMeta validatedMeta, AgentTaskSubscription subscription) {
        try {
            // 校验与接入之间隔了一次线程调度：重新读一次，
            // 避免把刚刚完成的任务误标成中断。
            AgentTaskMeta meta = loadMetaOrThrow(validatedMeta.getTaskId());
            assertExecutorHere(meta.getTaskId(), meta);
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

    /**
     * 保证"补发中断"这件事只由<b>执行者已经死亡</b>触发。
     *
     * <p>执行者是本机 → 正常（可能仍在启动窗口）；执行者是别的节点且租约有效 → 拒绝；
     * 执行者租约已过期 → 允许判定中断。旧实现没有这一层，多实例下会往
     * 正在生成的事件流里插 error+done，把活任务写成死任务。</p>
     */
    private void assertExecutorHere(String taskId, AgentTaskMeta meta) {
        if (meta.statusEnum().isTerminal()) {
            return;
        }
        AgentTaskState state = loadStateQuietly(taskId);
        if (state == null) {
            return;
        }
        String owner = state.getNodeId();
        if (owner == null || owner.equals(stateStore.nodeId())) {
            return;
        }
        if (isNodeAliveQuietly(owner)) {
            throw AgentTaskException.taskNotLocal(taskId, owner);
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
        // 状态说还有事件，但 Redis Stream 已经不在了（过期/被清理）
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
     * 执行者已消失：补发 error + done 到 Redis Stream，把状态收敛为 INTERRUPTED，
     * 让客户端明确失败而不是永久加载。
     *
     * <p>去重不再靠本机 {@code Set}，而是 Redis 的 SETNX 标记——多实例同时发现僵尸任务时，
     * 只有一个节点会真正追加事件。</p>
     */
    private AgentTaskMeta interruptStale(AgentTaskMeta meta) {
        String taskId = meta.getTaskId();
        if (!stateStore.markInterruptedOnce(taskId)) {
            AgentTaskMeta latest = taskMetaService.find(taskId);
            return latest != null ? latest : meta;
        }
        try {
            // 异步 attach 与任务线程可能并发：追加前再确认一次，
            // 避免把刚刚完成的任务标成中断，或与 done 事件撞 seq。
            AgentTaskMeta latest = taskMetaService.find(taskId);
            if (latest != null && latest.statusEnum().isTerminal()) {
                return latest;
            }
            if (latest != null) {
                meta = latest;
            }

            // 以 Redis Stream 尾部为准分配中断事件的 seq：运行中的状态水位可能滞后，
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
            taskMetaService.updateTerminalState(taskId, AgentTaskStatus.INTERRUPTED, doneSeq,
                    message, meta.getAssistantMessageId(), meta.isReplayable());
            persistInterruptedState(taskId, doneSeq, message);
            log.warn("[Task] 标记中断: taskId={}, lastSeq={}", taskId, doneSeq);
            AgentTaskMeta updated = taskMetaService.find(taskId);
            return updated != null ? updated : meta;
        } catch (Exception e) {
            // 收敛失败要放开标记，否则这个任务再也无法被判定中断
            stateStore.releaseInterruptedMarker(taskId);
            log.error("[Task] 中断标记失败: taskId={}", taskId, e);
            throw storeUnavailable(e);
        }
    }

    private void persistInterruptedState(String taskId, long lastSeq, String message) {
        try {
            AgentTaskState state = stateStore.load(taskId);
            if (state != null) {
                stateStore.markTerminal(taskId, AgentTaskStatus.INTERRUPTED, lastSeq, message,
                        state.getAssistantMessageId(), state.isReplayable());
            }
        } catch (Exception e) {
            log.warn("[Task] 中断状态写入 Redis 失败（MySQL 已收敛）: taskId={}, reason={}",
                    taskId, e.getMessage());
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

    /**
     * 任务元数据：优先用 Redis 运行时状态（实时、跨进程），缺失时回源 MySQL（落库权威）。
     */
    private AgentTaskMeta loadMetaOrThrow(String taskId) {
        AgentTaskState state = loadStateQuietly(taskId);
        if (state != null) {
            return state.toMeta();
        }
        AgentTaskMeta meta;
        try {
            meta = taskMetaService.find(taskId);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
        if (meta == null) {
            throw AgentTaskException.notFound(taskId);
        }
        return meta;
    }

    private AgentTaskState loadStateQuietly(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        try {
            return stateStore.load(taskId);
        } catch (Exception e) {
            log.warn("[Task] 运行时状态读取失败，回源 MySQL: taskId={}, reason={}",
                    taskId, e.getMessage());
            return null;
        }
    }

    private boolean isNodeAliveQuietly(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return false;
        }
        try {
            return stateStore.isNodeAlive(nodeId);
        } catch (Exception e) {
            log.warn("[Task] 节点租约读取失败: nodeId={}, reason={}", nodeId, e.getMessage());
            // 读不到租约时不轻易判定"执行者已死亡"，宁可让客户端重试
            return true;
        }
    }

    private void requireOwner(Long ownerUserId, Long userId) {
        if (ownerUserId == null || !Objects.equals(ownerUserId, userId)) {
            throw AgentTaskException.forbidden();
        }
    }

    private boolean isStarting(AgentTaskMeta meta) {
        // 只有"任务行已预留、消息还没绑定"的窗口需要宽限；
        // 已绑定 assistant 消息却不在本机，说明进程已重启或请求落到了别的节点。
        return !meta.statusEnum().isTerminal()
                && meta.getAssistantMessageId() == null
                && System.currentTimeMillis() - meta.getUpdatedAt() < STARTING_GRACE_MS;
    }

    private AgentTaskException storeUnavailable(Exception cause) {
        log.error("[Task] 任务存储不可用", cause);
        return new AgentTaskException(HttpStatus.SERVICE_UNAVAILABLE, "STORE_UNAVAILABLE",
                "任务事件归档暂不可用，请稍后重试");
    }

    private void leaseSafely() {
        try {
            stateStore.refreshNodeLease();
            long retentionMs = Math.max(1, properties.getReplayRetentionHours()) * 60 * 60 * 1000L;
            stateStore.pruneIndexesBefore(System.currentTimeMillis() - retentionMs);
        } catch (Exception e) {
            log.warn("[Task] 节点租约续期异常: {}", e.getMessage());
        }
    }

    private void heartbeatSafely() {
        for (AgentTask task : executions.values()) {
            try {
                if (task.isTerminal()) {
                    continue;
                }
                task.heartbeat();
                // 把 seq 水位刷进 Redis：其他节点的状态查询/中断编号都依赖它
                stateStore.touch(task.getTaskId(), task.getLastSeq());
            } catch (Exception e) {
                // 单个任务失败不能拖累其它任务的心跳，否则它们会被误判成执行者死亡
                log.warn("[Task] 心跳失败: taskId={}, reason={}", task.getTaskId(), e.getMessage());
            }
        }
    }

    /**
     * 把 Redis 里被别的节点置的取消标记推进本机执行上下文。
     */
    private void pollCancelSafely() {
        try {
            for (AgentTask task : executions.values()) {
                try {
                    task.pollCancelSignal();
                } catch (Exception e) {
                    log.debug("[Task] 取消信号同步失败: taskId={}, reason={}",
                            task.getTaskId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[Task] 取消巡检异常: {}", e.getMessage());
        }
    }

    private void cleanupSafely() {
        try {
            long retentionMs = Math.max(1, properties.getMemoryRetentionMinutes()) * 60_000L;
            long cutoff = System.currentTimeMillis() - retentionMs;
            // 本机句柄只是连接与回放缓存：终态超过保留期就可以丢，
            // 任务状态本身在 Redis 里按 replay-retention-hours 过期，不依赖这里。
            executions.entrySet().removeIf(entry -> {
                AgentTask task = entry.getValue();
                return task.isTerminal() && task.getFinishedAt() > 0
                        && task.getFinishedAt() < cutoff;
            });
            // 进程在 openReply 之前退出的占位任务：没有消息可恢复，直接删除让重试重新建任务。
            for (AgentTaskMeta stale : taskMetaService.findStaleReservations()) {
                if (!executions.containsKey(stale.getTaskId())) {
                    taskMetaService.discardUnbound(stale.getTaskId());
                    stateStore.remove(stale.getTaskId());
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
