package com.smartorder.edu.agent.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话摘要实体
 */
@Data
@NoArgsConstructor
public class ConversationSummary {
    private Long id;
    private String conversationId;
    private Long userId;
    private String title;
    private String summaryText;
    private Integer messageCount;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
