package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 学习规划草稿实体
 */
@Data
@NoArgsConstructor
public class AgentPlanDraft {
    private Long id;
    private String draftId;
    private String conversationId;
    private String traceId;
    private Long userId;
    private String planJson;

    /** TaskList JSON（PlanThenReAct 新架构） */
    private String taskListJson;

    /** 计划类型：LEGACY（旧 planJson） | TASK_LIST（新 TaskList） */
    private String planType;

    /** 断点恢复：当前执行到的任务索引 */
    private Integer resumeTaskIndex;

    private String status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
