package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认门请求——专用 API，用于 CONFIRMATION gate 的用户确认
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateConfirmRequest {

    /** 可选：关联的会话 ID，用于 SSE 事件上下文 */
    private String conversationId;
}
