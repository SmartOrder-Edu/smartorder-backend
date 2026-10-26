package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.exception.BudgetExceededException;
import com.smartorder.edu.agent.service.impl.AgentRunSupervisor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行预算——一次 Agent 运行的资源上限
 * <p>
 * 在 ReAct 循环和 Plan-and-Execute 主循环的每次迭代中检查是否超出预算，
 * 一旦超出则抛出 {@link BudgetExceededException}，由 {@link AgentRunSupervisor} 捕获并降级。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionBudget {

    /** 全局墙上超时（毫秒）。ReAct 默认 180s，Plan 默认 300s */
    private long globalTimeoutMs;

    /** 最大 LLM 调用次数。ReAct 默认 15，Plan 默认 50 */
    private int maxLlmCalls;

    /** 预算创建时间（毫秒时间戳） */
    @Builder.Default
    private long startTimeMs = System.currentTimeMillis();

    /** 已执行 LLM 调用次数 */
    @Builder.Default
    private AtomicInteger llmCallsMade = new AtomicInteger(0);

    /**
     * 是否已超出预算
     */
    public boolean isExceeded() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        return elapsed > globalTimeoutMs || llmCallsMade.get() > maxLlmCalls;
    }

    /**
     * 剩余可用时间（毫秒），最小为 0
     */
    public long remainingMs() {
        return Math.max(0, globalTimeoutMs - (System.currentTimeMillis() - startTimeMs));
    }

    /**
     * 记录一次 LLM 调用
     */
    public void recordLlmCall() {
        llmCallsMade.incrementAndGet();
    }

    /**
     * ReAct 模式默认预算：3 分钟，最多 15 次 LLM 调用
     */
    public static ExecutionBudget reactDefault() {
        return ExecutionBudget.builder()
                .globalTimeoutMs(180_000L)
                .maxLlmCalls(15)
                .build();
    }

    /**
     * Plan-and-Execute 模式默认预算：5 分钟，最多 50 次 LLM 调用
     */
    public static ExecutionBudget planDefault() {
        return ExecutionBudget.builder()
                .globalTimeoutMs(300_000L)
                .maxLlmCalls(50)
                .build();
    }
}
