package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.exception.BudgetExceededException;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.runtime.ExecutionBudget;
import com.smartorder.edu.agent.service.ConversationContextService;
import com.smartorder.edu.agent.service.ConversationMessageService;
import com.smartorder.edu.agent.service.TraceEventDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Agent 运行监督器——保证任何 Agent 运行最终收敛
 * <p>
 * 无论引擎是正常完成、超时、预算耗尽、还是抛出异常，都保证：
 * 1. 发送用户可读的降级消息（如果引擎没有自己保存 assistant 消息）
 * 2. 收尾本轮的 assistant 占位行（成功→COMPLETED，异常→FAILED + error_message）
 * 3. 正确关闭 SSE 连接（TRACE_COMPLETED + DONE）
 * <p>
 * 与改造前的区别：不再"新插一条兜底消息"，而是收尾 openReply 时创建的占位行，
 * 这样库里不会出现"一条失败记录 + 一条兜底消息"两条互相矛盾的数据。
 */
@Slf4j
@Component
public class AgentRunSupervisor {

    private final TraceEventDispatcher dispatcher;
    private final ConversationContextService contextService;
    private final ConversationMessageService messageService;
    private final ExecutorService supervisorExecutor;

    public AgentRunSupervisor(TraceEventDispatcher dispatcher,
                              ConversationContextService contextService,
                              ConversationMessageService messageService,
                              @Qualifier("agentChatExecutor") ExecutorService supervisorExecutor) {
        this.dispatcher = dispatcher;
        this.contextService = contextService;
        this.messageService = messageService;
        this.supervisorExecutor = supervisorExecutor;
    }

    /**
     * 退出原因——用于生成不同的降级消息
     */
    private enum ExitReason {
        NORMAL, GLOBAL_TIMEOUT, BUDGET_EXCEEDED, ENGINE_EXCEPTION, INTERRUPTED, UNEXPECTED
    }

    /**
     * 在监督下执行引擎逻辑，保证必定收敛
     * <p>
     * 引擎通过设置 {@code context.setAssistantMessageSaved(true)} 标记已保存消息。
     * 如果引擎未设置此标记（异常/超时/预算耗尽），supervisor 会自动生成降级回复并收尾占位行。
     *
     * @param context         运行时上下文（含 budget）
     * @param emitter         SSE 发射器
     * @param budget          执行预算
     * @param engineExecution 引擎执行逻辑
     */
    public void supervisedRun(AgentRuntimeContext context,
                              SseEmitter emitter,
                              ExecutionBudget budget,
                              Runnable engineExecution) {
        ExitReason exitReason = ExitReason.NORMAL;

        try {
            // 用独立线程执行引擎，带全局超时
            Future<?> future = supervisorExecutor.submit(engineExecution);
            long timeoutMs = budget.remainingMs();
            future.get(timeoutMs, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            exitReason = ExitReason.GLOBAL_TIMEOUT;
            log.warn("[Supervisor] 全局超时: traceId={}, budgetMs={}", context.getTraceId(), budget.getGlobalTimeoutMs());

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BudgetExceededException) {
                exitReason = ExitReason.BUDGET_EXCEEDED;
                log.warn("[Supervisor] 预算耗尽: traceId={}", context.getTraceId());
            } else {
                exitReason = ExitReason.ENGINE_EXCEPTION;
                log.error("[Supervisor] 引擎异常: traceId={}", context.getTraceId(), cause);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitReason = ExitReason.INTERRUPTED;
            log.warn("[Supervisor] 被中断: traceId={}", context.getTraceId());

        } catch (Exception e) {
            exitReason = ExitReason.UNEXPECTED;
            log.error("[Supervisor] 意外错误: traceId={}", context.getTraceId(), e);
        }

        // === 保证收敛：如果引擎没有保存 assistant 消息，自动生成降级回复并收尾占位行 ===
        if (!context.isAssistantMessageSaved()) {
            String fallback = buildFallbackMessage(exitReason);
            try {
                dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", fallback));
            } catch (Exception emitEx) {
                log.warn("[Supervisor] 降级消息推送失败: traceId={}", context.getTraceId());
            }
            closeTurn(context, fallback, exitReason);
        }

        // 始终正确关闭 SSE
        dispatcher.complete(emitter, context);
    }

    /**
     * 处理未被 supervisor 包裹的异常——保证收敛
     * <p>
     * 用于 orchestrator 中 supervisor 外部的异常路径（如路由前的错误）。
     */
    public void handleUncaughtError(AgentRuntimeContext context, SseEmitter emitter, Exception e) {
        log.error("[Supervisor] 未捕获异常: traceId={}", context.getTraceId(), e);

        if (!context.isAssistantMessageSaved()) {
            String fallback = "抱歉，处理过程中遇到了问题。请稍后重试，或尝试换一种方式描述你的需求。";
            try {
                dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", fallback));
            } catch (Exception emitEx) {
                log.warn("[Supervisor] 降级消息推送失败: traceId={}", context.getTraceId());
            }
            closeTurn(context, fallback, ExitReason.ENGINE_EXCEPTION);
        }

        dispatcher.complete(emitter, context);
    }

    /**
     * 收尾本轮占位行：
     * - 异常退出 → FAILED + error_message（前端可展示"生成失败，点击重试"）
     * - 正常退出但引擎没落库 → 命中占位行就 COMPLETED 收尾，未命中（历史路径）则退化为 INSERT
     */
    private void closeTurn(AgentRuntimeContext context, String fallback, ExitReason exitReason) {
        boolean closed = false;
        if (exitReason != ExitReason.NORMAL) {
            try {
                closed = messageService.failReply(context.getTraceId(), fallback, fallback);
            } catch (Exception ex) {
                log.error("[Supervisor] 失败收尾异常: traceId={}", context.getTraceId(), ex);
            }
        }
        if (!closed) {
            try {
                contextService.saveAssistantMessage(
                        context.getConversationId(),
                        context.getUserId(),
                        fallback,
                        null,
                        context.getTraceId(),
                        null
                );
            } catch (Exception saveEx) {
                log.error("[Supervisor] 保存 assistant 消息失败: traceId={}", context.getTraceId(), saveEx);
            }
        }
        context.setAssistantMessageSaved(true);
    }

    /**
     * 根据退出原因生成降级消息
     */
    private String buildFallbackMessage(ExitReason reason) {
        return switch (reason) {
            case GLOBAL_TIMEOUT -> "抱歉，当前任务处理时间较长，已自动停止。你可以尝试简化问题或稍后重试。";
            case BUDGET_EXCEEDED -> "抱歉，当前任务已达到计算资源上限。你可以尝试缩小问题范围或稍后继续。";
            case ENGINE_EXCEPTION -> "抱歉，处理过程中遇到了问题。请稍后重试，或尝试换一种方式描述你的需求。";
            case INTERRUPTED -> "任务已被中断。";
            case UNEXPECTED -> "抱歉，发生了意外错误。请稍后重试。";
            case NORMAL -> "抱歉，未能生成有效回复。请重新描述你的需求。";
        };
    }
}
