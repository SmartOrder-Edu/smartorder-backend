package com.smartorder.edu.common.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageDTO {
    private Long senderId;
    private Long receiverId;
    private String msgType;
    private String content;
    // 扩展字段：前端发送时不需要带，后端推送给接收方时可以带上消息ID和时间

    // 【新增】：前端生成的消息唯一标识（如 UUID），用于消息回执映射
    private String localId;
    private Long messageId;
    private String createTime;
    private LocalDateTime revoke_at;//撤回时间
}