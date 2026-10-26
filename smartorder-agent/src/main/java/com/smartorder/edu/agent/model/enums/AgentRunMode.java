package com.smartorder.edu.agent.model.enums;

import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 运行模式枚举
 * <p>
 * IntentType → AgentRunMode 映射关系：
 * <ul>
 *   <li>KNOWLEDGE_QA, PROBLEM_HELP, DIAGRAM_GENERATION, PROGRESS_REVIEW, TASK_MANAGEMENT, UNKNOWN → REACT</li>
 *   <li>GOAL_PLANNING, PLAN_CONFIRMATION, RESOURCE_GENERATION → PLAN_AND_EXECUTE</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum AgentRunMode {

    REACT("REACT", "简单问答：直接 ReAct 循环"),
    PLAN_AND_EXECUTE("PLAN_AND_EXECUTE", "复杂任务：先规划 ExecutionPlan，再逐步 ReAct 执行");

    private final String code;
    private final String description;
}
