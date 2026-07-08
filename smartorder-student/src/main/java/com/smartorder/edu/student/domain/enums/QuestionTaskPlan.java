package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 题目生成任务(task)的 SSE 事件类型枚举。
 *
 * <p>用于标识向前端推送的进度事件名称(eventName)与中文描述(description)，
 * persistable 标记该事件是否需持久化记录。</p>
 */
@Getter
@AllArgsConstructor
public enum QuestionTaskPlan {
    /**
     * 题目计划任务生成
     */
    QUESTION_TASK_PLAN("question_task_start", "计划生成", true),
     /**
     * 题目计划任务生成中
     */
    QUESTION_INSTANTIATE("question_task_instantiate", "题目生成中", true),
    /**
     * 计划生成完成
     */
    QUESTION_PLAN_COMPLETED("question_task_completed", "计划生成完成", true),
   /**
     * 生成失败
     */
    QUESTION_FAILED("question_task_failed", "题目生成失败", true);

    private final String eventName;
    private final String description;
    private final boolean persistable;
}
