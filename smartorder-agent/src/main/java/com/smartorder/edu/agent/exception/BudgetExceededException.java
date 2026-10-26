package com.smartorder.edu.agent.exception;

import com.smartorder.edu.agent.runtime.ExecutionBudget;
import com.smartorder.edu.agent.service.impl.AgentRunSupervisor;

/**
 * 执行预算耗尽异常
 * <p>
 * 由 {@link ExecutionBudget#isExceeded()} 检查触发，
 * 在 ReAct 循环或 Plan-and-Execute 主循环的每次迭代中抛出，
 * 由 {@link AgentRunSupervisor} 统一捕获并生成降级回复。
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }

    public BudgetExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
