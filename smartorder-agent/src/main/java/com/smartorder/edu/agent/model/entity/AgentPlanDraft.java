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
    private String status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
