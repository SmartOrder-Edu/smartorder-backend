package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {
    private String conversationId;
    private String title;
    private String lastMessage;
    private Integer messageCount;
    private LocalDateTime lastActiveTime;
}
