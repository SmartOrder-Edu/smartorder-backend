package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 运行状态实体 —— 对应 agent_run_state 表
 * <p>
 * 一次 Agent Run 的权威生命周期状态记录。
 * 状态流转：PLANNING → WAITING_CONFIRMATION → EXECUTING → PAUSED_* → COMPLETED/FAILED/CANCELLED
 */
@Data
@NoArgsConstructor
public class AgentRunState {
    private Long id;
    private String runId;
    private String conversationId;
    private String draftId;
    private String traceId;
    private Long userId;
    private String status;
    private Integer currentStepIndex;
    private String pausedReason;
    private String pausedTaskId;
    private String pausedClarificationId;
    private String pausedQuestion;
    private String resultSummary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime completedAt;
}
