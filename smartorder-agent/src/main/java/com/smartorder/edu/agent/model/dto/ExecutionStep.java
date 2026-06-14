package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 执行规划中的单个执行步骤。
 * <p>
 * ExecutionPlan 的组成单元。每个 ExecutionStep 描述 Agent 要执行的一个操作
 * （工具调用、用户澄清、或等待确认），不是学生的"学习任务"。
 * <p>
 * 每个步骤携带三件关键信息：
 * <ul>
 *   <li><b>requiredTools</b>：本步骤可用的工具（ReAct 循环的武器库）</li>
 *   <li><b>dependsOn</b>：依赖哪些前置步骤（控制执行顺序 + 可并行度）</li>
 *   <li><b>riskLevel</b>：副作用等级（决定是否需要确认门）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionStep {

    /** 步骤 ID，格式 step-1, step-2, ... */
    private String stepId;

    /** 步骤描述，注入 ReAct 上下文作为用户消息 */
    private String description;

    /** 本步骤需要的工具列表 */
    private List<String> requiredTools;

    /** 前置步骤 ID 列表 */
    @Builder.Default
    private List<String> dependsOn = List.of();

    /** 执行门类型：NORMAL | CLARIFICATION | CONFIRMATION */
    @Builder.Default
    private String gate = "NORMAL";

    /** 副作用等级：READ | WRITE_DRAFT | WRITE_CONFIRMED */
    @Builder.Default
    private String riskLevel = "READ";

    /** 预期产出描述（用于上下文积累和验证） */
    private String expectedOutput;

    /** 执行状态：PENDING | RUNNING | COMPLETED | FAILED | SKIPPED | PAUSED */
    @Builder.Default
    private String status = "PENDING";

    /** 执行结果摘要（执行后填充） */
    private String resultSummary;

    /** 失败原因 */
    private String errorMessage;

    /** 重试次数（动态重规划时递增） */
    @Builder.Default
    private int retryCount = 0;
}
