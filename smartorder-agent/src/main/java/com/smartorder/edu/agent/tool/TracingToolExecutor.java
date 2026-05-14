package com.smartorder.edu.agent.tool;

import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具执行安全层——所有工具调用必须经过此执行器
 *
 * 执行链路：
 * 参数解析 → 权限校验 → 推送 TOOL_STARTED → 执行工具 → 结果截断 → 推送 TOOL_COMPLETED/FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TracingToolExecutor {

    private final TraceEventDispatcher dispatcher;
    private final ToolPermissionChecker permissionChecker;
    private final ToolResultTruncator resultTruncator;

    /**
     * 执行工具，带完整追踪和审计
     */
    public Object execute(ToolHandler handler, Object arguments, ToolInvokeContext context) {
        long start = System.currentTimeMillis();

        // 1. 权限校验
        permissionChecker.check(handler, arguments, context);

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

        // 3. 执行工具
        try {
            Object result = handler.execute(arguments, context);
            long duration = System.currentTimeMillis() - start;

            // 4. 结果截断
            Object truncatedResult = resultTruncator.truncate(result);

            // 5. 推送 TOOL_COMPLETED
            dispatcher.emit(
                    context.getEmitter(),
                    context.getRuntimeContext(),
                    AgentEventType.TOOL_COMPLETED,
                    Map.of(
                            "toolName", handler.name(),
                            "result", truncatedResult,
                            "durationMs", duration
                    )
            );

            log.info("[TracingToolExecutor] {} 执行成功, 耗时{}ms", handler.name(), duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[TracingToolExecutor] {} 执行失败, 耗时{}ms", handler.name(), duration, e);

            // 6. 推送 TOOL_FAILED
            dispatcher.emit(
                    context.getEmitter(),
                    context.getRuntimeContext(),
                    AgentEventType.TOOL_FAILED,
                    Map.of(
                            "toolName", handler.name(),
                            "error", e.getMessage(),
                            "durationMs", duration
                    )
            );

            throw new RuntimeException("工具 [" + handler.name() + "] 执行失败: " + e.getMessage(), e);
        }
    }
}
