package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.config.AgentConfiguration;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 会话记忆服务——替代 AgentConfiguration 中的纯内存 ChatMemory
 *
 * 上下文组装策略：
 * System Prompt + 会话摘要 + 最近 30 条消息（含当前用户消息，因为 orchestrator 已提前落库）
 */
public interface ConversationMemoryService {

    String getSystemPrompt();

    List<ChatMessage> buildMessages(String conversationId, Long userId);

    void saveUserMessage(String conversationId, Long userId, String content, List<Long> attachmentFileIds);

    void saveAssistantMessage(String conversationId, Long userId, String content, String reasoningContent, String traceId, String metadataJson);

    void saveToolMessage(String conversationId, Long userId, String toolName, String content);

    int countUncompressed(String conversationId);

    void deleteConversation(String conversationId, Long userId);

    String getSummaryText(String conversationId);

    String getUncompressedText(String conversationId);

    String getRecentConversationText(String conversationId, int maxMessages);

    List<ChatMessage> getRecentChatMessages(String conversationId, int maxMessages);

    String extractRecentGoal(String conversationId);
}
