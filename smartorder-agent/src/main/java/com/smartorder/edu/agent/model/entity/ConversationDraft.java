package com.smartorder.edu.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 新对话草稿与 conversation_id 的一对一绑定。
 *
 * <p>{@code draftId} 由前端每次“新建对话”生成；同一个草稿在重试时必须复用
 * 同一个 {@code draftId} 和 {@code conversationId}。MySQL 的唯一键是最终防线：
 * <ul>
 *   <li>{@code draft_id} 主键：一个草稿只能绑定一个会话；</li>
 *   <li>{@code uk_draft_conversation_id}：一个会话只能属于一个草稿。</li>
 * </ul>
 * </p>
 */
@Data
@NoArgsConstructor
@TableName("agent_conversation_draft")
public class ConversationDraft {

    @TableId(value = "draft_id", type = IdType.INPUT)
    private String draftId;

    private String conversationId;

    private Long userId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
