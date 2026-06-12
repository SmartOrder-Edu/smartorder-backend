package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 计划中的单个任务项
 * <p>
 * TaskList 的组成单元，每个 TaskItem 携带三件关键信息：
 * - requiredTools：本任务需要的工具（ReAct 循环的武器库）
 * - dependsOn：依赖哪些前置任务（控制执行顺序 + 可并行度）
 * - riskLevel：副作用等级（决定是否需要确认门）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskItem {

    /** 任务 ID，格式 task-1, task-2, ... */
    private String taskId;

    /** 任务描述，注入 ReAct 上下文 */
    private String description;

    /** 本任务需要的工具 */
    private List<String> requiredTools;

    /** 前置任务 ID 列表 */
    @Builder.Default
    private List<String> dependsOn = List.of();

    /** 执行门类型：NORMAL | CLARIFICATION | CONFIRMATION */
    @Builder.Default
    private String gate = "NORMAL";

    /** 副作用等级：READ | WRITE_DRAFT | WRITE_CONFIRMED */
    @Builder.Default
    private String riskLevel = "READ";

    /** 预期产出描述（用于上下文积累） */
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
