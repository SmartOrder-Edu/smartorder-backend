package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行规划——Plan-and-Execute 架构中规划与执行的桥梁。
 * <p>
 * Plan 阶段产出 ExecutionPlan（Agent 要执行的步骤列表），
 * Execute 阶段遍历 ExecutionPlan 逐步骤执行（每个步骤走 ReAct 循环）。
 * <p>
 * 注意：ExecutionPlan 描述的是 <b>Agent 自身的执行步骤</b>（调什么工具、按什么顺序），
 * 不是学生的<b>学习计划</b>。学习计划由最后一个步骤产出（LearningPlan），
 * 经用户确认后创建学习空间。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionPlan {

    /** 用户原始目标 */
    private String userGoal;

    /** 计划 ID（UUID，保存时生成） */
    private String planId;

    /** 所有执行步骤（按拓扑排序后的顺序） */
    private List<ExecutionStep> steps;

    /**
     * 整体状态：
     * DRAFT → EXECUTING → PAUSED_CLARIFICATION / PAUSED_CONFIRMATION → COMPLETED / FAILED / CANCELLED
     */
    @Builder.Default
    private String status = "DRAFT";

    /** 当前执行到的步骤索引（断点恢复用） */
    @Builder.Default
    private int currentStepIndex = 0;

    /** 已完成步骤的输出（key=stepId, value=结果摘要），注入后续步骤上下文 */
    @Builder.Default
    private Map<String, String> stepOutputs = new LinkedHashMap<>();

    /** 创建时间戳 */
    private Long createdAt;

    /** PAUSED_CLARIFICATION 时填充的追问文本 */
    private String clarificationQuestion;

    /** PAUSED_CONFIRMATION 时填充的草稿 ID */
    private String confirmationDraftId;
}
