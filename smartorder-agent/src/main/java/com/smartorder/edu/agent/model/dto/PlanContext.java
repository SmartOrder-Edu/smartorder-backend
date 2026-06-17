package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化计划上下文——替代 stepOutputs 中 __clarification.* 的文本拼接
 * <p>
 * 在执行子任务时，由 buildTaskSystemMessage() 将 PlanContext 注入到 prompt 中，
 * 而不修改 task.description 原文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanContext {

    /** 用户目标 */
    private String userGoal;

    /** 用户画像快照（来自 getStudentProfile 或澄清答案） */
    private String profileSnapshot;

    /** 附件摘要 */
    private String attachmentSummary;

    /** 澄清答案: slotKey → answer */
    @Builder.Default
    private Map<String, String> clarificationAnswers = new LinkedHashMap<>();

    /** 已完成步骤的产出: stepId → result（非 __ 前缀的 stepOutputs） */
    @Builder.Default
    private Map<String, String> stepOutputs = new LinkedHashMap<>();
}
