package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum QuestionJobPlan {
    /**
     * 题目计划任务生成
     */
    QUESTION_JOB_PLAN("question_job_start", "计划生成", true),
     /**
     * 题目计划任务生成中
     */
    QUESTION_INSTANTIATE("question_job_instantiate", "题目生成中", true),
    /**
     * 计划生成完成
     */
    QUESTION_PLAN_COMPLETED("question_job_completed", "计划生成完成", true),
   /**
     * 生成失败
     */
    QUESTION_FAILED("question_job_failed", "题目生成失败", true);

    private final String eventName;
    private final String description;
    private final boolean persistable;
}
