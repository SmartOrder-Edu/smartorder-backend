package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话消息持久化实体
 */
@Data
@NoArgsConstructor
public class ConversationMessage {
    private Long id;
    private String conversationId;
    private Long userId;
    private String role;
    private String content;
    private String reasoningContent;
    private String toolName;
    private String metadataJson;
    private Integer isCompressed;
    private Integer isDeleted;
    private LocalDateTime createTime;
}
