package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 规划草稿实体——存储两种类型的规划数据：
 * <ul>
 *   <li><b>EXECUTION_PLAN</b>：Agent 执行步骤列表（Plan-and-Execute 模式产出）</li>
 *   <li><b>LEARNING_PLAN</b>：学习计划草稿（含阶段、路径、知识图谱），用户审阅确认后创建学习空间</li>
 * </ul>
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

    /** ExecutionPlan JSON（当 planType=EXECUTION_PLAN 时有值） */
    private String executionPlanJson;

    /** 计划类型：EXECUTION_PLAN | LEARNING_PLAN */
    private String planType;

    /** 断点恢复：当前执行到的步骤索引 */
    private Integer resumeStepIndex;

    private String status;
    /** 该草稿确认后创建的学习空间 ID（幂等回查用，仅 LEARNING_PLAN 类型有值） */
    private Long createdSpaceId;
    /** 确认时间 */
    private LocalDateTime confirmedAt;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
