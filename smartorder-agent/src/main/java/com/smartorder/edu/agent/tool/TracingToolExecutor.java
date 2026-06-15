package com.smartorder.edu.agent.tool;

import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 工具执行安全层——所有工具调用必须经过此执行器
 *
 * 执行链路：
 * 参数解析 → 权限校验 → 推送 TOOL_STARTED → 执行工具（带超时）→ 结果截断 → 推送 TOOL_COMPLETED/FAILED
 * <p>
 * 工具执行失败时返回 {@link ToolResult#error(String)} 而非抛异常，
 * 让 ReAct 循环可以继续，而非进入异常路径。
 */
@Slf4j
@Component
public class TracingToolExecutor {

    private final TraceEventDispatcher dispatcher;
    private final ToolPermissionChecker permissionChecker;
    private final ToolResultTruncator resultTruncator;
    private final ExecutorService toolExecutorService;

    public TracingToolExecutor(TraceEventDispatcher dispatcher,
                               ToolPermissionChecker permissionChecker,
                               ToolResultTruncator resultTruncator,
                               @Qualifier("toolExecutionExecutor") ExecutorService toolExecutorService) {
        this.dispatcher = dispatcher;
        this.permissionChecker = permissionChecker;
        this.resultTruncator = resultTruncator;
        this.toolExecutorService = toolExecutorService;
    }

    /**
     * 执行工具，带完整追踪、超时和审计
     * <p>
     * 失败时返回 {@link ToolResult#error(String)} 而非抛异常，
     * 让调用方可以继续 ReAct 循环。
     */
    public Object execute(ToolHandler handler, Object arguments, ToolInvokeContext context) {
        long start = System.currentTimeMillis();

        // 1. 权限校验（失败返回 ToolResult 而非抛异常）
        try {
            permissionChecker.check(handler, arguments, context);
        } catch (Exception e) {
            log.warn("[TracingToolExecutor] 权限校验失败: tool={}", handler.name(), e);
            return ToolResult.error("权限不足，无法执行工具 [" + handler.name() + "]", e.getMessage());
        }

        // 2. 推送 TOOL_STARTED
        dispatcher.emit(
                context.getEmitter(),
                context.getRuntimeContext(),
                AgentEventType.TOOL_STARTED,
                Map.of(
                        "toolName", handler.name(),
                        "arguments", arguments
                )
        );

        // 3. 计算工具超时时间（基于剩余预算）
        long toolTimeoutMs = deriveToolTimeout(context.getRuntimeContext());

        // 4. 执行工具（带超时和重试）
        int maxRetries = handler.maxRetries();
        long retryDelayMs = handler.retryDelayMs();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                log.info("[TracingToolExecutor] {} 重试第{}次", handler.name(), attempt);
                try {
                    Thread.sleep(Math.min(retryDelayMs * (1L << (attempt - 1)), 10_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                Future<Object> future = toolExecutorService.submit(
                        () -> handler.execute(arguments, context));
                Object result = future.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
                long duration = System.currentTimeMillis() - start;

                // 5. 结果截断
                Object truncatedResult = resultTruncator.truncate(result);

                // 6. 推送 TOOL_COMPLETED
                Map<String, Object> completedPayload = new LinkedHashMap<>();
                completedPayload.put("toolName", handler.name());
                completedPayload.put("result", truncatedResult);
                completedPayload.put("durationMs", duration);
                if (context.getAttributes() != null) {
                    Object webSearchRefs = context.getAttributes().get("webSearchReferences");
                    if (webSearchRefs != null) {
                        completedPayload.put("webSearchReferences", webSearchRefs);
                    }
                }
                dispatcher.emit(
                        context.getEmitter(),
                        context.getRuntimeContext(),
                        AgentEventType.TOOL_COMPLETED,
                        completedPayload
                );

                log.info("[TracingToolExecutor] {} 执行成功, 耗时{}ms", handler.name(), duration);
                return result;

            } catch (TimeoutException e) {
                long duration = System.currentTimeMillis() - start;
                log.error("[TracingToolExecutor] {} 执行超时, 耗时{}ms, timeout={}ms",
                        handler.name(), duration, toolTimeoutMs);
                lastException = e;
                // 超时不重试，直接返回错误
                break;

            } catch (ExecutionException e) {
                lastException = e.getCause() != null ? (Exception) e.getCause() : e;
                log.warn("[TracingToolExecutor] {} 执行失败 (attempt {}): {}",
                        handler.name(), attempt, lastException.getMessage());
                // 如果还有重试次数，继续循环
                if (attempt < maxRetries) {
                    continue;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long duration = System.currentTimeMillis() - start;
                dispatcher.emit(
                        context.getEmitter(),
                        context.getRuntimeContext(),
                        AgentEventType.TOOL_FAILED,
                        Map.of(
                                "toolName", handler.name(),
                                "error", "执行被中断",
                                "durationMs", duration
                        )
                );
                return ToolResult.error("工具 [" + handler.name() + "] 执行被中断");

            } catch (Exception e) {
                lastException = e;
                log.warn("[TracingToolExecutor] {} 执行异常 (attempt {}): {}",
                        handler.name(), attempt, e.getMessage());
                if (attempt < maxRetries) {
                    continue;
                }
            }
        }

        // 所有尝试均失败，返回结构化错误
        long duration = System.currentTimeMillis() - start;
        String errorMsg = lastException instanceof TimeoutException
                ? "执行超时（" + toolTimeoutMs + "ms）"
                : (lastException != null ? lastException.getMessage() : "未知错误");

        dispatcher.emit(
                context.getEmitter(),
                context.getRuntimeContext(),
                AgentEventType.TOOL_FAILED,
                Map.of(
                        "toolName", handler.name(),
                        "error", errorMsg,
                        "durationMs", duration
                )
        );

        return ToolResult.error("工具 [" + handler.name() + "] " + errorMsg, errorMsg);
    }

    /**
     * 根据执行预算计算单个工具调用的超时时间
     * <p>
     * 策略：取剩余时间的 60%，clamp 到 [10s, 60s]；无预算时默认 30s
     */
    private long deriveToolTimeout(AgentRuntimeContext context) {
        if (context == null || context.getBudget() == null) {
            return 30_000L; // 默认 30 秒
        }
        long remaining = context.getBudget().remainingMs();
        // 使用剩余时间的 60%，最少 10s，最多 60s
        return Math.max(10_000L, Math.min(60_000L, (long) (remaining * 0.6)));
    }
}
