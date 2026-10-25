package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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
     * 学习执行页传入的当前环境上下文。
     * 用于“这里没听懂”等指代性问题，避免 Agent 脱离当前教学脚本回答。
     */
    private Map<String, Object> studyContext;

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

    /**
     * 客户端幂等键（可选，建议必传）。
     * <p>同一用户 + 同一 clientRequestId 只会创建一次生成：响应丢失后重试、
     * 或断线后重新创建，都会复用原来的生成任务，不会重复执行模型调用。</p>
     */
    private String clientRequestId;
}
