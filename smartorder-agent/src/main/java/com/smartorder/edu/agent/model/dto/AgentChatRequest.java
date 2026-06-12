package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 对话请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatRequest {
    private Long userId;
    private String message;
    private String conversationId;
    private List<Long> fileIds;

    /**
     * 强制运行模式（可选），跳过意图路由。
     * "REACT" — 快速回答；"PLAN_AND_EXECUTE" — 目标规划。
     * 为 null 时走正常的意图路由。
     */
    private String forceMode;
}
