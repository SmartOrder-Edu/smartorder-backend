package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.vo.AgentTaskVO;
import com.smartorder.edu.agent.runtime.AgentEventSink;
import com.smartorder.edu.agent.runtime.AgentTask;
import com.smartorder.edu.agent.runtime.AgentTaskRegistry;
import com.smartorder.edu.agent.runtime.AgentTaskSubscriptionPlan;
import com.smartorder.edu.agent.service.AgentChatService;
import com.smartorder.edu.agent.service.AgentOrchestrator;
import com.smartorder.edu.agent.service.ConversationMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 对话服务——发消息、订阅回复流、查状态、取消。
 *
 * <p><b>为什么发送与订阅必须拆开</b>：</p>
 * <ol>
 *   <li>发送接口在返回前同步完成事务 A（任务行、会话、用户消息、assistant 占位行），
 *       因此可以把 taskId / conversationId / messageId 直接返回；即使 SSE 一次都没连上，
 *       前端也知道这段对话落在哪、该查哪个任务。</li>
 *   <li>生成在返回后异步执行，与连接彻底无关；SSE 订阅是只读的，断线重连只是重新订阅。</li>
 *   <li>发送可以安全重试：同一 clientRequestId 命中 MySQL 唯一键直接复用，不会重复插入消息或重复生成。</li>
 * </ol>
 */
@Slf4j
@Service
public class AgentChatServiceImpl implements AgentChatService {

    private final AgentTaskRegistry registry;

    private final AgentOrchestrator orchestrator;

    private final ConversationMessageService conversationMessageService;

    private final ExecutorService taskExecutor;

    public AgentChatServiceImpl(AgentTaskRegistry registry,
                                AgentOrchestrator orchestrator,
                                ConversationMessageService conversationMessageService,
                                @Qualifier("agentTaskExecutor") ExecutorService taskExecutor) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.conversationMessageService = conversationMessageService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 创建（或幂等复用）一个对话任务：先抢任务行，再落消息，最后异步启动编排。
     */
    @Override
    public AgentTaskVO sendMessage(AgentChatRequest request, Long userId, String clientRequestId) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new AgentTaskException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "消息不能为空");
        }
        String idempotencyKey = normalize(clientRequestId);

        // 事务 A 之前先抢 MySQL 任务行：唯一键 (user_id, client_request_id) 是幂等的最终防线。
        String taskId = UUID.randomUUID().toString();
        AgentTaskRegistry.Reservation reservation = registry.reserve(
                userId, taskId, idempotencyKey, request.getConversationId());
        if (!reservation.created()) {
            AgentTaskVO existing = registry.status(reservation.taskId(), userId);
            existing.setCreated(false);
            log.info("[Task] 命中幂等键，复用已有任务: taskId={}, userId={}, key={}",
                    reservation.taskId(), userId, idempotencyKey);
            return existing;
        }

        String traceId = UUID.randomUUID().toString();
        AgentTurnContext turn;
        try {
            ConversationMessageService.PendingReply state = conversationMessageService.openReply(
                    request.getConversationId(), userId, request.getMessage(),
                    request.getFileIds(), traceId, reservation.taskId());
            turn = AgentTurnContext.from(state, traceId);
        } catch (RuntimeException e) {
            // 会话已删除 / 无权访问 / 数据库异常：删掉未绑定消息的占位任务，允许客户端重试
            registry.discardReservation(reservation.taskId());
            log.warn("[Task] 插入消息失败: userId={}, cid={}, reason={}",
                    userId, request.getConversationId(), e.getMessage());
            throw new AgentTaskException(HttpStatus.BAD_REQUEST, "TURN_OPEN_FAILED",
                    e.getMessage() != null ? e.getMessage() : "会话不可用");
        }
        request.setConversationId(turn.getConversationId());

        AgentTask task = registry.bindTurn(reservation.taskId(), userId, idempotencyKey, turn);
        startAsync(task, request, turn);

        return AgentTaskVO.builder()
                .taskId(task.getTaskId())
                .conversationId(turn.getConversationId())
                .traceId(traceId)
                .userMessageId(turn.getUserMessageId())
                .assistantMessageId(turn.getAssistantMessageId())
                .assistantSequenceNo(turn.getAssistantSequenceNo())
                .title(turn.getTitle())
                .status(task.getStatus().name())
                .created(true)
                .lastSeq(0L)
                .replayable(true)
                .createdAt(task.getCreatedAt())
                .build();
    }

    /**
     * 同步校验订阅：鉴权、游标、可回放性。不向连接写任何字节，失败时抛
     * {@link AgentTaskException}，控制器可在响应提交前返回 403/404/410。
     */
    @Override
    public AgentTaskSubscriptionPlan validateSubscription(String taskId, Long userId,
                                                          long afterSeq, AgentEventSink sink) {
        return registry.validateSubscription(taskId, userId, afterSeq, sink);
    }

    /**
     * 异步接入订阅：回放 Redis Stream 积压 + 接实时。提交后立即返回，控制器可以先返回 SseEmitter，
     * 浏览器不必等整个 backlog 回放完才收到响应。
     *
     * @throws java.util.concurrent.RejectedExecutionException 执行线程池已满
     */
    @Override
    public void attachAsync(AgentTaskSubscriptionPlan plan) {
        taskExecutor.execute(() -> {
            try {
                registry.attach(plan);
            } catch (Throwable t) {
                // 响应已经提交，不能再改成 HTTP 状态码；只能异常收口
                log.error("[Task] 异步接入订阅失败: taskId={}",
                        plan.subscription().getTaskId(), t);
                plan.subscription().completeWithError(t);
            }
        });
    }

    @Override
    public AgentTaskVO status(String taskId, Long userId) {
        return registry.status(taskId, userId);
    }

    @Override
    public AgentTaskVO activeTask(String conversationId, Long userId) {
        return registry.activeTask(conversationId, userId);
    }

    @Override
    public Map<String, Object> cancel(String taskId, Long userId) {
        boolean accepted = registry.cancel(taskId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        // 兼容旧客户端的只读别名，新代码不再使用
        result.put("generationId", taskId);
        result.put("cancelAccepted", accepted);
        return result;
    }

    /**
     * “发送接口与生成执行”之间的解耦点：Agent 输出只写入任务事件日志（内存 + Redis Stream），
     * 不绑定任何一条 SSE 连接。
     *
     * @param task    本次任务
     * @param request 原始请求
     * @param turn    已落库的本轮消息
     */
    private void startAsync(AgentTask task, AgentChatRequest request, AgentTurnContext turn) {
        try {
            taskExecutor.execute(() -> {
                try {
                    // emitter 传 null：所有输出都经由任务事件日志，不再绑定某一条连接
                    orchestrator.handle(request, null, task.getTaskId(), turn);
                } catch (Throwable t) {
                    log.error("[Task] 编排线程异常退出: taskId={}", task.getTaskId(), t);
                    registry.fail(task.getTaskId(),
                            t.getMessage() != null ? t.getMessage() : "任务执行失败");
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Task] 线程池拒绝任务: taskId={}", task.getTaskId(), e);
            registry.fail(task.getTaskId(), "系统繁忙，任务未能启动，请稍后重试");
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
