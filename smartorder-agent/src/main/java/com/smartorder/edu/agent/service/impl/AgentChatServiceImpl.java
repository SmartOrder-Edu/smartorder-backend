package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.exception.AgentGenerationException;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.vo.AgentGenerationVO;
import com.smartorder.edu.agent.runtime.AgentEventSink;
import com.smartorder.edu.agent.runtime.AgentGeneration;
import com.smartorder.edu.agent.runtime.AgentGenerationRegistry;
import com.smartorder.edu.agent.runtime.AgentGenerationSubscriptionPlan;
import com.smartorder.edu.agent.service.AgentChatService;
import com.smartorder.edu.agent.service.AgentOrchestrator;
import com.smartorder.edu.agent.service.ConversationMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 对话服务——发消息、订阅回复流、查状态、取消。
 *
 * <p><b>为什么发送与订阅必须拆开</b>：</p>
 * <ol>
 *   <li>发送接口在返回前同步完成事务 A（会话、用户消息、assistant 占位行），
 *       因此可以把 conversationId / messageId 直接返回；即使 SSE 一次都没连上，
 *       前端也知道这段对话落在哪、消息是哪一条。</li>
 *   <li>生成在返回后异步执行，与连接彻底无关；SSE 订阅是只读的，断线重连只是重新订阅。</li>
 *   <li>发送可以安全重试：同一 clientRequestId 命中幂等直接复用，不会重复插入消息或重复生成。</li>
 * </ol>
 */
@Slf4j
@Service
public class AgentChatServiceImpl implements AgentChatService { 

    private final AgentGenerationRegistry manager;

    private final AgentOrchestrator orchestrator;

    private final ConversationMessageService conversationMessageService;

    private final ExecutorService generationExecutor;

    public AgentChatServiceImpl(AgentGenerationRegistry manager,
                                  AgentOrchestrator orchestrator,
                                  ConversationMessageService conversationMessageService,
                                  @Qualifier("agentGenerationExecutor") ExecutorService generationExecutor) {
        this.manager = manager;
        this.orchestrator = orchestrator;
        this.conversationMessageService = conversationMessageService;
        this.generationExecutor = generationExecutor;
    }

    /**
     * 创建（或幂等复用）一次生成：同步落库并返回会话/消息 ID，再异步启动编排。
     */
    public AgentGenerationVO sendMessage(AgentChatRequest request, Long userId, String clientRequestId) {
        String idempotencyKey = normalize(clientRequestId);

        // 这是用来判断任务是否重复生成，保持幂等性的
        String existingId = manager.findGenerationIdByClientRequest(userId, idempotencyKey);
        if (existingId != null) {
            try {
                AgentGenerationVO existing = manager.status(existingId, userId);
                existing.setCreated(false);
                log.info("[Generation] 命中幂等键，复用已有生成: generationId={}, userId={}, key={}",
                        existingId, userId, idempotencyKey);
                return existing;
            } catch (AgentGenerationException e) {
                log.warn("[Generation] 幂等键指向的生成不可用，将新建: key={}, reason={}",
                        idempotencyKey, e.getMessage());
            }
        }

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new AgentGenerationException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "消息不能为空");
        }

        // ===== 事务 A：先落库（会话 + 用户消息 + assistant 占位行），拿到对外 ID =====
        String traceId = UUID.randomUUID().toString();
        AgentTurnContext turn;
        try {
            ConversationMessageService.PendingReply state = conversationMessageService.openReply(
                    request.getConversationId(), userId, request.getMessage(), request.getFileIds(), traceId);
            turn = AgentTurnContext.from(state, traceId);
        } catch (RuntimeException e) {
            // 会话已删除 / 无权访问 / 数据库异常：发送接口直接失败，不创建半截生成任务
            log.warn("[Generation] 插入消息失败: userId={}, cid={}, reason={}",
                    userId, request.getConversationId(), e.getMessage());
            throw new AgentGenerationException(HttpStatus.BAD_REQUEST, "TURN_OPEN_FAILED",
                    e.getMessage() != null ? e.getMessage() : "会话不可用");
        }
        request.setConversationId(turn.getConversationId());

        AgentGeneration generation = manager.create(userId, idempotencyKey, turn);
        startAsync(generation, request, turn);

        return AgentGenerationVO.builder()
                .generationId(generation.getGenerationId())
                .conversationId(turn.getConversationId())
                .traceId(traceId)
                .userMessageId(turn.getUserMessageId())
                .assistantMessageId(turn.getAssistantMessageId())
                .assistantSequenceNo(turn.getAssistantSequenceNo())
                .title(turn.getTitle())
                .status(generation.getStatus().name())
                .created(true)
                .lastSeq(0L)
                .replayable(true)
                .createdAt(generation.getCreatedAt())
                .build();
    }

    /**
     * 同步校验订阅：鉴权、游标、可回放性。不向连接写任何字节，失败时抛
     * {@link AgentGenerationException}，控制器可在响应提交前返回 403/404/410。
     */
    public AgentGenerationSubscriptionPlan validateSubscription(String generationId, Long userId,
                                                               long afterSeq, AgentEventSink sink) {
        return manager.validateSubscription(generationId, userId, afterSeq, sink);
    }

    /**
     * 异步接入订阅：回放积压 + 接实时。提交后立即返回，控制器可以先返回 SseEmitter，
     * 浏览器不必等整个 backlog 回放完才收到响应。
     *
     * @throws java.util.concurrent.RejectedExecutionException 生成线程池已满
     */
    public void attachAsync(AgentGenerationSubscriptionPlan plan) {
        generationExecutor.execute(() -> {
            try {
                manager.attach(plan);
            } catch (Throwable t) {
                // 响应已经提交，不能再改成 HTTP 状态码；只能异常收口
                log.error("[Generation] 异步接入订阅失败: generationId={}",
                        plan.subscription().getGenerationId(), t);
                plan.subscription().completeWithError(t);
            }
        });
    }

    public AgentGenerationVO status(String generationId, Long userId) {
        return manager.status(generationId, userId);
    }

    public AgentGenerationVO activeGeneration(String conversationId, Long userId) {
        return manager.activeGeneration(conversationId, userId);
    }

    public Map<String, Object> cancel(String generationId, Long userId) {
        boolean accepted = manager.cancel(generationId, userId);
        return Map.of(
                "generationId", generationId,
                "cancelAccepted", accepted
        );
    }

    /** “发送接口与生成执行”之间的解耦点：Agent 端自动响应消息存储到 Redis
     *
     * @param generation 本次生成
     * @param request    原始请求
     * @param turn       已落库的本轮消息
     */
    private void startAsync(AgentGeneration generation, AgentChatRequest request, AgentTurnContext turn) {
        try {
            generationExecutor.execute(() -> {
                try {
                    // emitter 传 null：所有输出都经由生成事件日志，不再绑定某一条连接
                    orchestrator.handle(request, null, generation.getGenerationId(), turn);
                } catch (Throwable t) {
                    log.error("[Generation] 编排线程异常退出: generationId={}", generation.getGenerationId(), t);
                    manager.fail(generation.getGenerationId(),
                            t.getMessage() != null ? t.getMessage() : "生成执行失败");
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Generation] 线程池拒绝生成任务: generationId={}", generation.getGenerationId(), e);
            manager.fail(generation.getGenerationId(), "系统繁忙，生成任务未能启动，请稍后重试");
        }
    }

    private String normalize(String clientRequestId) {
        if (clientRequestId == null) {
            return null;
        }
        String trimmed = clientRequestId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
