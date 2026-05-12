package com.smartorder.edu.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 对话请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatRequest {
    private String message;
    private String conversationId;
}
