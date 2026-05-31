package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum QuestionPlan {
    /**
     * 计划生成
     */
    PLAN("question_plan_start", "计划生成", true),
    /**
     * 立即生成
     */
    IMMEDIATE("question_immediate_start", "立即生成", false),
    /**
     * 计划生成中
     */
    PLANNING("question_planning", "计划生成", true),
    /**
     * 题目生成中
     */
    INSTANTIATE("question_instantiate", "题目生成中", true),
    /**
     * 计划生成完成
     */
    PLAN_COMPLETED("question_plan_completed", "计划生成完成", true),
    /**
     * 立即生成完成
     */
    IMMEDIATE_COMPLETED("question_immediate_completed", "立即生成完成", false),
    /**
     * 生成失败
     */
    FAILED("question_generation_failed", "题目生成失败", true);

    private final String eventName;
    private final String description;
    private final boolean persistable;
}
