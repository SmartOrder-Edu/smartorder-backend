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
     * 知识库ID集合（用于 RAG 检索）
     */
    private List<Long> knowledgeBaseIds;

    /**
     * RAG 检索模式（可选）：auto/simple/medium/complex
     * 为 null 时默认使用 auto
     */
    private String ragRetrievalMode;

    /**
     * 强制运行模式（可选），跳过意图路由。
     * "REACT" — 快速回答；"PLAN_AND_EXECUTE" — 目标规划。
     * 为 null 时走正常的意图路由。
     */
    private String forceMode;

    /**
     * 结构化澄清回答 JSON（可选），优先于 message 字段。
     * 格式: {"clarificationId": "uuid", "answer": "用户回答"}
     * 前端提交结构化澄清答案时设置此字段。
     */
    private String clarificationAnswerJson;
}
