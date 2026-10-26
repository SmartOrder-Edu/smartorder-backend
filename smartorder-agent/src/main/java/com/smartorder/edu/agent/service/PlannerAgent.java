package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.ExecutionPlan;

/**
 * 任务规划智能体——Plan-and-Execute 架构的规划阶段。
 * <p>
 * 将用户目标拆解为有序的 ExecutionPlan（Agent 执行步骤列表），
 * 每个步骤携带 requiredTools、dependsOn、gate、riskLevel。
 * <p>
 * ExecutionPlan 描述的是 Agent 自身的执行步骤；学习计划（路径+KG+阶段）
 * 由执行步骤中的 {@code createLearningPlan} 工具产出。
 * <p>
 * SystemMessage 在构建 AiServices 时通过
 * {@code .systemMessageProvider(memoryId -> promptLoader.getPlannerCreateSystemMessage())} 注入。
 */
public interface PlannerAgent {

    String createExecutionPlan(String prompt);

    String replan(String prompt);
}
