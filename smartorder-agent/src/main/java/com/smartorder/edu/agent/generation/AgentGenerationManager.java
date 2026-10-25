package com.smartorder.edu.agent.generation;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.AgentTraceEventDTO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 生成任务管理器——连接与生成之间的唯一中介。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>创建生成任务并分配 generationId（含幂等键、会话索引）；</li>
 *   <li>把 {@code TraceEventDispatcher} 的事件写入生成的内存日志并推给订阅者；</li>
 *   <li>处理订阅请求：同步校验归属/游标 → 异步回放内存日志并接入实时，或从 Redis 归档回放；</li>
 *   <li>终态收敛：写 done、关订阅、落终态元数据；</li>
 *   <li>心跳与过期清理。</li>
 * </ol>
 *
 * <p><b>单实例语义</b>：一个生成只会在创建它的 JVM 中执行。若元数据存在但本机没有运行时对象，
 * 说明进程已重启（单机部署）或请求落到了别的节点（多实例），此时标记 INTERRUPTED 并回放
 * 已有事件——绝不假装任务还在跑，也绝不重新执行一次业务。</p>
 */
@Slf4j
@Component
public class AgentGenerationManager {

    private final AgentGenerationEventStore store;

    private final AgentGenerationEventMirror mirror;

    private final AgentSseProperties properties;

    /** 本机正在执行的生成（含刚结束、仍在内存保留期的生成） */
    private final Map<String, AgentGeneration> live = new ConcurrentHashMap<>();

    /** 已经补发过“中断”事件的生成，避免重复追加 */
    private final Set<String> interrupted = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService housekeeping = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "generation-housekeeping");
        thread.setDaemon(true);
        return thread;
    });

    public AgentGenerationManager(AgentGenerationEventStore store,
                                  AgentGenerationEventMirror mirror,
                                  AgentSseProperties properties) {
        this.store = store;
        this.mirror = mirror;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        int heartbeatSeconds = properties.getHeartbeatSeconds();
        if (heartbeatSeconds > 0) {
            housekeeping.scheduleAtFixedRate(this::heartbeatSafely,
                    heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        }
        housekeeping.scheduleAtFixedRate(this::cleanupSafely, 60, 60, TimeUnit.SECONDS);
        log.info("[Generation] 管理器已启动: store={}, heartbeat={}s",
                properties.getEventStore(), heartbeatSeconds);
    }

    @PreDestroy
    public void shutdown() {
        housekeeping.shutdownNow();
    }

    // ============ 创建与绑定 ============

    /**
     * 新建一次生成任务。
     *
     * <p>此时事务 A 已经完成（会话、用户消息、assistant 占位行都已落库），
     * 所以会话与消息 ID 可以直接写进元数据并返回给前端，不必等 SSE 首包。</p>
     *
     * <p>归档失败不会阻止生成本身，只会标记为“仅本机可回放”。</p>
     */
    public AgentGeneration create(Long userId, String clientRequestId, AgentTurnContext turn) {
        String generationId = UUID.randomUUID().toString();
        AgentGeneration generation = new AgentGeneration(
                generationId, userId, clientRequestId, turn, System.currentTimeMillis(),
                (gen, event) -> mirror.append(gen.getGenerationId(), event));
        live.put(generationId, generation);

        try {
            store.saveMeta(toMeta(generation));
            if (clientRequestId != null && !clientRequestId.isBlank()) {
                store.bindClientRequest(userId, clientRequestId, generationId);
            }
            if (generation.getConversationId() != null) {
                store.bindConversation(generation.getConversationId(), generationId);
            }
        } catch (Exception e) {
            generation.markReplayDegraded();
            log.error("[Generation] 元数据归档失败，本次生成仅支持本机重连: generationId={}", generationId, e);
        }
        log.info("[Generation] 创建生成: generationId={}, userId={}, cid={}, userMsgId={}, assistantMsgId={}, clientRequestId={}",
                generationId, userId, generation.getConversationId(),
                generation.getUserMessageId(), generation.getAssistantMessageId(), clientRequestId);
        return generation;
    }

    public AgentGeneration getLive(String generationId) {
        return generationId == null ? null : live.get(generationId);
    }

    public void bindContext(String generationId, AgentRuntimeContext context) {
        AgentGeneration generation = getLive(generationId);
        if (generation != null && context != null) {
            generation.bindContext(context);
        }
    }

    /**
     * 会话 ID 在 openTurn 之后才确定，绑定后刷新恢复才能找到这次生成。
     */
    public void bindConversation(String generationId, String conversationId) {
        if (generationId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        AgentGeneration generation = getLive(generationId);
        if (generation != null) {
            generation.bindConversation(conversationId);
        }
        try {
            store.bindConversation(conversationId, generationId);
            store.updateConversation(generationId, conversationId);
        } catch (Exception e) {
            log.warn("[Generation] 会话索引写入失败: generationId={}, cid={}", generationId, conversationId, e);
        }
    }

    public String findGenerationIdByClientRequest(Long userId, String clientRequestId) {
        if (userId == null || clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return store.findGenerationByClientRequest(userId, clientRequestId);
        } catch (Exception e) {
            log.warn("[Generation] 幂等键查询失败: userId={}, clientRequestId={}", userId, clientRequestId, e);
            return null;
        }
    }

    // ============ 事件发布 ============

    /**
     * 发布业务事件。返回 null 表示“没有生成上下文”或“生成已终态”。
     */
    public AgentGenerationEvent publish(AgentRuntimeContext context, AgentEventType eventType, Object payload) {
        AgentGeneration generation = liveOf(context);
        if (generation == null) {
            return null;
        }
        return generation.append(context, eventType, payload);
    }

    /**
     * 发布只走在线连接、不进入回放日志的即时事件。
     */
    public void publishEphemeral(AgentRuntimeContext context, String eventName, Object payload) {
        AgentGeneration generation = liveOf(context);
        if (generation != null) {
            generation.publishEphemeral(context, eventName, payload);
        }
    }

    /**
     * 结束生成：写 done 事件 → 刷归档 → 落终态。重复调用安全（终态只发一次）。
     */
    public void finish(AgentRuntimeContext context, Map<String, Object> donePayload,
                       AgentGenerationStatus terminalStatus, String errorMessage) {
        AgentGeneration generation = liveOf(context);
        if (generation == null) {
            return;
        }
        if (generation.finish(context, donePayload, terminalStatus, errorMessage) == null) {
            return;
        }
        mirror.flush(generation.getGenerationId());
        persistTerminalState(generation, terminalStatus, errorMessage);
    }

    /**
     * 编排线程意外退出时的兜底收敛（编排器自身会捕获所有异常，这里是最后一道保险）。
     */
    public void fail(String generationId, String errorMessage) {
        AgentGeneration generation = getLive(generationId);
        if (generation == null || generation.getStatus().isTerminal()) {
            return;
        }
        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("message", errorMessage);
        generation.appendNamed(null, AgentEventNames.ERROR, errorPayload);

        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("conversationId", generation.getConversationId());
        donePayload.put("traceId", generation.getTraceId());
        donePayload.put("error", errorMessage);
        generation.finish(null, donePayload, AgentGenerationStatus.FAILED, errorMessage);

        mirror.flush(generationId);
        persistTerminalState(generation, AgentGenerationStatus.FAILED, errorMessage);
    }

    public boolean isCancelRequested(AgentRuntimeContext context) {
        AgentGeneration generation = liveOf(context);
        return generation != null && generation.isCancelRequested();
    }

    // ============ 订阅与回放 ============

    /**
     * 同步校验订阅请求：归属、游标、可回放性。只读内存/元数据，不向连接写任何字节。
     *
     * <p>必须在控制器返回 SseEmitter 之前调用：SSE 响应一旦开始，403/404/410
     * 就无法再作为 HTTP 状态码返回，只能退化成连接错误。</p>
     *
     * @return 校验通过后的接入计划，交给 {@link #attach(AgentGenerationSubscriptionPlan)} 执行
     */
    public AgentGenerationSubscriptionPlan validateSubscription(String generationId, Long userId,
                                                               long afterSeq, AgentEventSink sink) {
        if (userId == null) {
            throw AgentGenerationException.forbidden();
        }
        if (afterSeq < 0) {
            throw AgentGenerationException.invalidCursor(afterSeq);
        }

        // 1. 本机正在执行：内存日志权威，校验只读 lastSeq
        AgentGeneration generation = live.get(generationId);
        if (generation != null) {
            requireOwner(generation.getUserId(), userId);
            if (afterSeq > generation.getLastSeq()) {
                throw AgentGenerationException.invalidCursor(afterSeq);
            }
            return AgentGenerationSubscriptionPlan.live(generation,
                    new AgentGenerationSubscription(generationId, afterSeq, sink));
        }

        // 2. 本机没有运行时对象：校验元数据与归档游标
        AgentGenerationMeta meta = loadMetaOrThrow(generationId);
        requireOwner(meta.getUserId(), userId);
        // 运行中的 Redis 元数据不随每条事件更新 lastSeq，以归档尾部为准，
        // 否则进程重启后会误判“游标超前”或把中断事件写成重复 seq。
        long lastSeq = Math.max(meta.getLastSeq(), lastEventSeqOrThrow(generationId));
        if (afterSeq > lastSeq) {
            throw AgentGenerationException.invalidCursor(afterSeq);
        }
        if (!meta.isReplayable() && afterSeq < lastSeq) {
            throw AgentGenerationException.cursorExpired(afterSeq);
        }
        // 归档缺口也属于游标过期。只读第一条事件，避免把整个 backlog 拉进请求线程。
        if (afterSeq < lastSeq) {
            AgentGenerationEvent next = readFirstAfterOrThrow(generationId, afterSeq);
            if (next != null && next.getSeq() > afterSeq + 1) {
                throw AgentGenerationException.cursorExpired(afterSeq);
            }
        }
        return AgentGenerationSubscriptionPlan.archived(meta,
                new AgentGenerationSubscription(generationId, afterSeq, sink));
    }

    /**
     * 真正接入订阅：回放积压并登记实时推送。
     *
     * <p>可能被慢连接背压，必须跑在生成线程池上；本机生成在回放期间仍持有 generation 锁，
     * 但 servlet 线程已经返回，浏览器可以立即收到首字节。</p>
     */
    public void attach(AgentGenerationSubscriptionPlan plan) {
        if (plan.isLive()) {
            plan.liveGeneration().attach(plan.subscription());
            return;
        }
        attachArchive(plan.archivedMeta(), plan.subscription());
    }

    public AgentGenerationVO status(String generationId, Long userId) {
        if (userId == null) {
            throw AgentGenerationException.forbidden();
        }
        AgentGeneration generation = live.get(generationId);
        if (generation != null) {
            requireOwner(generation.getUserId(), userId);
            return toVO(generation);
        }
        AgentGenerationMeta meta = loadMetaOrThrow(generationId);
        requireOwner(meta.getUserId(), userId);
        // 运行中进程已死时，元数据里的 lastSeq 只在终态更新；以归档尾部为准，
        // 否则前端做“游标超前”对账会拿到过期的 0。归档暂时不可读时退回元数据，
        // 状态查询是兜底接口，不应该因为一次 Redis 抖动而整体失败。
        long lastSeq = meta.getLastSeq();
        try {
            lastSeq = Math.max(lastSeq, store.lastEventSeq(generationId));
        } catch (Exception e) {
            log.warn("[Generation] 状态查询读取归档尾部失败，使用元数据 lastSeq: generationId={}, reason={}",
                    generationId, e.getMessage());
        }
        return toVO(meta, lastSeq);
    }

    /**
     * 会话当前（最近一次）生成，用于刷新页面后恢复。
     *
     * @return 不存在或不属于当前用户时返回 null
     */
    public AgentGenerationVO activeGeneration(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank() || userId == null) {
            return null;
        }
        String generationId;
        try {
            generationId = store.findActiveGeneration(conversationId);
        } catch (Exception e) {
            log.warn("[Generation] 会话索引查询失败: cid={}", conversationId, e);
            return null;
        }
        if (generationId == null) {
            return null;
        }
        try {
            return status(generationId, userId);
        } catch (AgentGenerationException e) {
            log.debug("[Generation] 会话索引指向的生成不可用: cid={}, generationId={}, reason={}",
                    conversationId, generationId, e.getMessage());
            return null;
        }
    }

    /**
     * 请求取消。取消是协作式的：引擎在循环边界检查标记，已提交的副作用不自动回滚。
     *
     * @return true 表示已把取消标记送达正在执行的生成
     */
    public boolean cancel(String generationId, Long userId) {
        if (userId == null) {
            throw AgentGenerationException.forbidden();
        }
        AgentGeneration generation = live.get(generationId);
        if (generation == null) {
            AgentGenerationMeta meta = loadMetaOrThrow(generationId);
            requireOwner(meta.getUserId(), userId);
            return false;
        }
        requireOwner(generation.getUserId(), userId);
        if (generation.getStatus().isTerminal()) {
            return false;
        }
        generation.cancel();
        log.info("[Generation] 收到取消请求: generationId={}, userId={}", generationId, userId);
        return true;
    }

    // ============ 内部 ============

    private AgentGeneration liveOf(AgentRuntimeContext context) {
        if (context == null || context.getGenerationId() == null) {
            return null;
        }
        return live.get(context.getGenerationId());
    }

    private void persistTerminalState(AgentGeneration generation, AgentGenerationStatus status, String error) {
        try {
            store.updateState(generation.getGenerationId(), status, generation.getLastSeq(),
                    error, generation.getAssistantMessageId());
        } catch (Exception e) {
            log.error("[Generation] 终态写入失败: generationId={}, status={}",
                    generation.getGenerationId(), status, e);
        }
    }

    private void attachArchive(AgentGenerationMeta validatedMeta, AgentGenerationSubscription subscription) {
        try {
            // 校验与接入之间隔了一次线程调度：重新读元数据，
            // 避免把刚刚完成的生成误标成中断。
            AgentGenerationMeta meta = store.getMeta(validatedMeta.getGenerationId());
            if (meta == null) {
                throw AgentGenerationException.notFound(validatedMeta.getGenerationId());
            }
            if (!meta.statusEnum().isTerminal()) {
                meta = interruptStale(meta);
            }
            replayFromArchive(meta, subscription);
        } catch (AgentGenerationException e) {
            log.info("[Generation] 归档回放被拒绝: generationId={}, status={}, reason={}",
                    subscription.getGenerationId(), e.getStatus().value(), e.getMessage());
            subscription.completeWithError(e);
        } catch (Exception e) {
            log.error("[Generation] 归档回放失败: generationId={}", subscription.getGenerationId(), e);
            subscription.completeWithError(storeUnavailable(e));
        }
    }

    private AgentGenerationEvent readFirstAfterOrThrow(String generationId, long afterSeq) {
        try {
            return store.readFirstAfter(generationId, afterSeq);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
    }

    private long lastEventSeqOrThrow(String generationId) {
        try {
            return store.lastEventSeq(generationId);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
    }

    private void replayFromArchive(AgentGenerationMeta meta, AgentGenerationSubscription subscription) {
        // 先把心跳写出去，让浏览器立刻拿到首字节；随后才是可能较慢的归档读取。
        if (!subscription.sendHeartbeat()) {
            subscription.complete();
            return;
        }
        long afterSeq = subscription.getAfterSeq();
        List<AgentGenerationEvent> backlog;
        try {
            backlog = store.readAfter(meta.getGenerationId(), afterSeq);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
        // 归档不完整：绝不静默跳到最新事件，让客户端明确知道无法保证完整回放
        if (!backlog.isEmpty() && backlog.get(0).getSeq() > afterSeq + 1) {
            throw AgentGenerationException.cursorExpired(afterSeq);
        }
        if (!meta.isReplayable() && afterSeq < meta.getLastSeq()) {
            throw AgentGenerationException.cursorExpired(afterSeq);
        }
        for (AgentGenerationEvent event : backlog) {
            if (!subscription.deliver(event)) {
                log.info("[Generation] 归档回放中断: generationId={}, 已补发到 seq={}",
                        meta.getGenerationId(), subscription.getLastSentSeq());
                subscription.complete();
                return;
            }
        }
        subscription.complete();
    }

    /**
     * 进程重启/请求落到别的节点：元数据还停在 CREATED/RUNNING，但本机没有执行者。
     * 补发一条 error + done，把状态收敛为 INTERRUPTED，让客户端明确失败而不是永久加载。
     */
    private AgentGenerationMeta interruptStale(AgentGenerationMeta meta) {
        String generationId = meta.getGenerationId();
        if (!interrupted.add(generationId)) {
            AgentGenerationMeta latest = store.getMeta(generationId);
            return latest != null ? latest : meta;
        }
        try {
            // 异步 attach 与生成线程可能并发：追加前再确认一次，
            // 避免把刚刚完成的生成标成中断，或与 done 事件撞 seq。
            AgentGenerationMeta latest = store.getMeta(generationId);
            if (latest != null && latest.statusEnum().isTerminal()) {
                return latest;
            }
            if (latest != null) {
                meta = latest;
            }

            // 以归档尾部为准分配中断事件的 seq：运行中的 Redis 元数据 lastSeq 可能还是 0，
            // 直接 +1 会与已有事件撞号，客户端会把它当成重复游标丢掉。
            long errorSeq = Math.max(meta.getLastSeq(), lastEventSeqOrThrow(generationId)) + 1;
            String message = "生成已中断：服务端已不再持有该任务，只能回放已产生的内容。";
            store.appendEvent(generationId, syntheticEvent(meta, AgentEventNames.ERROR, errorSeq,
                    Map.of("message", message)));
            long doneSeq = errorSeq + 1;
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("conversationId", meta.getConversationId());
            donePayload.put("generationId", generationId);
            donePayload.put("status", AgentGenerationStatus.INTERRUPTED.name());
            donePayload.put("error", message);
            // done 与在线链路保持一致：扁平结构，不套事件信封
            store.appendEvent(generationId, new AgentGenerationEvent(doneSeq, AgentEventNames.DONE,
                    JSON.toJSONString(donePayload), System.currentTimeMillis()));
            store.updateState(generationId, AgentGenerationStatus.INTERRUPTED, doneSeq, message, null);
            log.warn("[Generation] 标记中断: generationId={}, lastSeq={}", generationId, doneSeq);
            AgentGenerationMeta updated = store.getMeta(generationId);
            return updated != null ? updated : meta;
        } catch (Exception e) {
            log.error("[Generation] 中断标记失败: generationId={}", generationId, e);
            throw storeUnavailable(e);
        }
    }

    private AgentGenerationEvent syntheticEvent(AgentGenerationMeta meta, String eventName,
                                                long seq, Object payload) {
        AgentTraceEventDTO dto = AgentTraceEventDTO.builder()
                .generationId(meta.getGenerationId())
                .traceId(meta.getTraceId())
                .conversationId(meta.getConversationId())
                .userId(meta.getUserId())
                .eventType(eventName)
                .seq((int) seq)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
        return new AgentGenerationEvent(seq, eventName, JSON.toJSONString(dto), dto.getTimestamp());
    }

    private AgentGenerationMeta loadMetaOrThrow(String generationId) {
        AgentGenerationMeta meta;
        try {
            meta = store.getMeta(generationId);
        } catch (Exception e) {
            throw storeUnavailable(e);
        }
        if (meta == null) {
            throw AgentGenerationException.notFound(generationId);
        }
        return meta;
    }

    private void requireOwner(Long ownerUserId, Long userId) {
        if (ownerUserId == null || !Objects.equals(ownerUserId, userId)) {
            throw AgentGenerationException.forbidden();
        }
    }

    private AgentGenerationException storeUnavailable(Exception cause) {
        log.error("[Generation] 事件归档不可用", cause);
        return new AgentGenerationException(HttpStatus.SERVICE_UNAVAILABLE, "STORE_UNAVAILABLE",
                "生成事件归档暂不可用，请稍后重试");
    }

    private void heartbeatSafely() {
        try {
            for (AgentGeneration generation : live.values()) {
                if (!generation.getStatus().isTerminal()) {
                    generation.heartbeat();
                }
            }
        } catch (Exception e) {
            log.warn("[Generation] 心跳任务异常: {}", e.getMessage());
        }
    }

    private void cleanupSafely() {
        try {
            long retentionMs = Math.max(1, properties.getMemoryRetentionMinutes()) * 60_000L;
            long cutoff = System.currentTimeMillis() - retentionMs;
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, AgentGeneration> entry : live.entrySet()) {
                AgentGeneration generation = entry.getValue();
                if (generation.getStatus().isTerminal() && generation.getFinishedAt() > 0
                        && generation.getFinishedAt() < cutoff) {
                    expired.add(entry.getKey());
                }
            }
            for (String generationId : expired) {
                live.remove(generationId);
                interrupted.remove(generationId);
            }
            if (!expired.isEmpty()) {
                log.debug("[Generation] 清理内存中的终态生成: {}", expired.size());
            }
        } catch (Exception e) {
            log.warn("[Generation] 清理任务异常: {}", e.getMessage());
        }
    }

    private AgentGenerationMeta toMeta(AgentGeneration generation) {
        return AgentGenerationMeta.builder()
                .generationId(generation.getGenerationId())
                .conversationId(generation.getConversationId())
                .userId(generation.getUserId())
                .traceId(generation.getTraceId())
                .clientRequestId(generation.getClientRequestId())
                .status(generation.getStatus().name())
                .createdAt(generation.getCreatedAt())
                .updatedAt(System.currentTimeMillis())
                .lastSeq(generation.getLastSeq())
                .userMessageId(generation.getUserMessageId())
                .assistantMessageId(generation.getAssistantMessageId())
                .assistantSequenceNo(generation.getAssistantSequenceNo())
                .title(generation.getTitle())
                .errorMessage(generation.getErrorMessage())
                .replayable(!generation.isReplayDegraded())
                .build();
    }

    private AgentGenerationVO toVO(AgentGeneration generation) {
        return AgentGenerationVO.builder()
                .generationId(generation.getGenerationId())
                .conversationId(generation.getConversationId())
                .traceId(generation.getTraceId())
                .userMessageId(generation.getUserMessageId())
                .assistantMessageId(generation.getAssistantMessageId())
                .assistantSequenceNo(generation.getAssistantSequenceNo())
                .title(generation.getTitle())
                .status(generation.getStatus().name())
                .lastSeq(generation.getLastSeq())
                .errorMessage(generation.getErrorMessage())
                .replayable(!generation.isReplayDegraded())
                .createdAt(generation.getCreatedAt())
                .build();
    }

    private AgentGenerationVO toVO(AgentGenerationMeta meta, long lastSeq) {
        return AgentGenerationVO.builder()
                .generationId(meta.getGenerationId())
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
