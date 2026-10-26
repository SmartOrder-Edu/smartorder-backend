package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话列表/详情 VO。
 * <p>
 * 只加字段不删字段：旧前端读取 conversationId / title / lastMessage / messageCount / lastActiveTime 不受影响。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {
    /** 对外会话ID（兼容旧前端的字段名，值等于 conversation_uuid） */
    private String conversationId;
    /** 对外会话ID（= conversation.conversation_uuid） */
    private String conversationUuid;
    private String title;
    /** 最后一条消息摘要（列表副标题） */
    private String lastMessage;
    private Integer messageCount;
    /** 最后活跃时间（列表排序键） */
    private LocalDateTime lastActiveTime;

    /** ACTIVE / ARCHIVED / DELETED */
    private String status;
    /** 是否置顶 */
    private Integer pinned;
    /** 会话创建时间 */
    private LocalDateTime createdAt;
    /** 与 lastActiveTime 同值，语义化别名，便于新前端直接使用 */
    private LocalDateTime lastMessageAt;
}
