package com.smartorder.edu.agent.route;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 运行模式枚举
 * <p>
 * IntentType → AgentRunMode 映射关系：
 * <ul>
 *   <li>KNOWLEDGE_QA, PROBLEM_HELP, DIAGRAM_GENERATION, PROGRESS_REVIEW, TASK_MANAGEMENT, UNKNOWN → REACT</li>
 *   <li>GOAL_PLANNING, PLAN_CONFIRMATION, RESOURCE_GENERATION → PLAN_THEN_REACT</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum AgentRunMode {

    REACT("REACT", "简单问答：直接 ReAct 循环"),
    PLAN_THEN_REACT("PLAN_THEN_REACT", "复杂任务：先规划 TaskList，再逐任务 ReAct 执行");

    private final String code;
    private final String description;
}
