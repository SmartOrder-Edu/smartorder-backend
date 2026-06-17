package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 澄清记录实体 —— 对应 agent_clarification 表
 * <p>
 * 每个 slot-question 一行，强制"一次只问一个"原则。
 * 状态流转：PENDING → ANSWERED → ACKNOWLEDGED（或 SKIPPED）
 */
@Data
@NoArgsConstructor
public class AgentClarification {
    private Long id;
    private String clarificationId;
    private String conversationId;
    private String traceId;
    private Long userId;
    private String draftId;
    private String taskId;
    private String slotKey;
    private String question;
    private String answerType;
    private String optionsJson;
    private String answer;
    private String status;
    private String ackMessage;
    private Integer slotPriority;
    private LocalDateTime createTime;
    private LocalDateTime answerTime;
    private LocalDateTime ackTime;
}
