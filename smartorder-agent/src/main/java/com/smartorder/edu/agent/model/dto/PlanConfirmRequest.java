package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规划确认请求——只传 draftId，不让前端传完整 plan
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanConfirmRequest {
    private String draftId;
    private String conversationId;
}
