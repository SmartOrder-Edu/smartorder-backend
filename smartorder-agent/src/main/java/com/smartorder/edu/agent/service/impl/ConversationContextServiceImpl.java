package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.config.AgentConfiguration;
import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.mapper.ConversationSummaryMapper;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.entity.ConversationSummary;
import com.smartorder.edu.agent.service.ConversationContextService;
import com.smartorder.edu.agent.service.ConversationService;
import com.smartorder.edu.agent.service.ConversationMessageService;
import com.smartorder.edu.agent.util.ConfirmationWords;
import com.smartorder.edu.agent.util.PromptLoader;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文服务——替代 AgentConfiguration 中的纯内存 ChatMemory
 *
 * 上下文组装策略：
 * System Prompt + 会话摘要 + 最近 30 条消息（含当前用户消息，因为 orchestrator 已提前落库）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextServiceImpl implements ConversationContextService { 

    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final AgentTraceEventMapper traceEventMapper;
    private final PromptLoader promptLoader;
    private final ConversationMessageService messageService;
    private final ConversationService conversationService;

    private static final int MAX_RECENT_MESSAGES = 30;

    /**
     * 获取系统提示词（供 PlanAndExecute 引擎的 executeWithTaskContext 使用）
     */
    public String getSystemPrompt() {
        return promptLoader.getMainAgentSystemPrompt();
    }

    /**
     * 构建发给 LLM 的历史上下文消息列表（包含当前用户消息）
     *
     * 注意：AgentOrchestratorImpl 在调用引擎前已将当前用户消息保存到 DB，
     * 因此 selectRecentMessages 会包含当前用户消息，调用方不应再手动追加。
     */
    public List<ChatMessage> buildMessages(String conversationId, Long userId) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System Prompt
        messages.add(promptLoader.getMainAgentSystemMessage());

        // 2. 会话摘要（如果存在）
        ConversationSummary summary = summaryMapper.selectByConversationId(conversationId);
        if (summary != null && summary.getSummaryText() != null && !summary.getSummaryText().isBlank()) {
            messages.add(SystemMessage.from("【历史会话摘要】" + summary.getSummaryText()));
        }

        // 3. 最近 30 条历史消息（包含当前消息，因为 orchestrator 在调用引擎前已落库）
        List<ConversationMessage> recentMessages = messageMapper.selectRecentMessages(conversationId, MAX_RECENT_MESSAGES);
        // selectRecentMessages 按 create_time DESC 返回，需要反转顺序
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = recentMessages.get(i);
            // 跳过已压缩的
            if (msg.getIsCompressed() != null && msg.getIsCompressed() == 1) continue;
            ChatMessage chatMsg = toChatMessage(msg);
            if (chatMsg != null) {
                messages.add(chatMsg);
            }
        }

        log.info("[MemoryService] 构建上下文: conversationId={}, summary={}, recentMsgs={}, totalMsgs={}",
                conversationId,
                summary != null ? "有" : "无",
                recentMessages.size(),
                messages.size());

        return messages;
    }

    /**
     * 保存用户消息。
     * <p>
     * 首条消息不再走这里——它由 {@link ConversationMessageService#openReply} 在事务 A 里
     * 与 assistant 占位行一起落库。这里服务于"一轮内追加的用户消息"
     * （如计划澄清回答、计划确认文本）。
     */
    public void saveUserMessage(String conversationId, Long userId, String content, List<Long> attachmentFileIds) {
        messageService.appendUserMessage(conversationId, userId, content, attachmentFileIds);
    }

    /**
     * 保存助手消息。
     * <p>
     * 语义保持"一次调用 = 一条气泡"，但写入方式变了：
     * 1) 命中本轮占位行 → UPDATE 收尾（RUNNING → COMPLETED），15 处调用点无需改动；
     * 2) 未命中（同一轮第二次调用 / 计划执行等非 openReply 路径）→ INSERT 一条新消息，
     *    序号由会话行锁分配，不会与占位行冲突。
     */
    public void saveAssistantMessage(String conversationId, Long userId, String content,
                                     String reasoningContent, String traceId, String metadataJson) {
        if (messageService.completeReply(traceId, content, reasoningContent, metadataJson)) {
            return;
        }
        messageService.appendAssistantMessage(conversationId, userId, content, reasoningContent, traceId, metadataJson);
    }

    /**
     * 保存工具消息（is_visible=0，占序号但不计入可见消息数、不进模型上下文）
     */
    public void saveToolMessage(String conversationId, Long userId, String toolName, String content) {
        messageService.appendToolMessage(conversationId, userId, toolName, content);
    }

    /**
     * 获取未压缩消息数量
     */
    public int countUncompressed(String conversationId) {
        return messageMapper.countUncompressed(conversationId);
    }

    /**
     * 删除对话（软删会话 + 软删消息和摘要，硬删除追踪事件）
     * <p>
     * 先软删会话主表：这样后续任何按 conversation_id 的写入都会被
     * {@link ConversationMessageService} 的会话状态校验挡住。
     */
    @Transactional
    public void deleteConversation(String conversationId, Long userId) {
        conversationService.softDelete(conversationId, userId);

        int msgCount = messageMapper.softDeleteByConversationId(conversationId, userId);
        log.info("[MemoryService] 软删除消息: conversationId={}, count={}", conversationId, msgCount);

        int summaryCount = summaryMapper.softDeleteByConversationId(conversationId, userId);
        log.info("[MemoryService] 软删除摘要: conversationId={}, count={}", summaryCount);

        int traceCount = traceEventMapper.deleteByConversationId(conversationId);
        log.info("[MemoryService] 硬删除追踪事件: conversationId={}, count={}", traceCount);
    }

    /**
     * 获取会话摘要（供 Planner 等需要上下文的地方使用）
     */
    public String getSummaryText(String conversationId) {
        ConversationSummary summary = summaryMapper.selectByConversationId(conversationId);
        if (summary != null && summary.getSummaryText() != null && !summary.getSummaryText().isBlank()) {
            return summary.getSummaryText();
        }
        return null;
    }

    // ============ Private ============

    private ChatMessage toChatMessage(ConversationMessage msg) {
        if (msg.getContent() == null) return null;
        switch (msg.getRole()) {
            case "user":
                return UserMessage.from(msg.getContent());
            case "assistant":
                // 重建 AiMessage 时，如果有 reasoning_content 则带上 thinking，
                // 否则 MIMO thinking mode 会报 400 错误
                if (msg.getReasoningContent() != null && !msg.getReasoningContent().isBlank()) {
                    return AiMessage.builder()
                            .text(msg.getContent())
                            .thinking(msg.getReasoningContent())
                            .build();
                }
                return AiMessage.from(msg.getContent());
            case "system":
                return SystemMessage.from(msg.getContent());
            // tool 消息暂不加载到上下文
            default:
                return null;
        }
    }

    /**
     * 获取未压缩消息的文本（用于增量摘要）
     * 只取 is_compressed = 0 的消息，按时间正序拼接
     */
    public String getUncompressedText(String conversationId) {
        List<ConversationMessage> messages = messageMapper.selectUncompressedMessages(conversationId);
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage msg : messages) {
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            sb.append("[").append(msg.getRole()).append("]: ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 获取最近 N 条对话消息的格式化文本（用于画像分析等异步任务）
     * 跳过空内容消息，按时间正序排列。
     *
     * @param conversationId 会话 ID
     * @param maxMessages 最大消息数
     * @return 格式化的历史文本，如 "[user]: xxx\n\n[assistant]: yyy\n\n"
     */
    public String getRecentConversationText(String conversationId, int maxMessages) {
        List<ConversationMessage> messages = messageMapper.selectRecentMessages(conversationId, maxMessages);
        // selectRecentMessages 按 create_time DESC 返回，需要反转顺序
        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            sb.append("[").append(msg.getRole()).append("]: ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 获取最近 N 条对话消息的结构化列表（用于 LLM 调用）
     * 跳过空内容消息，按时间正序排列（user/assistant 交替）。
     *
     * @param conversationId 会话 ID
     * @param maxMessages    最大消息数
     * @return LangChain4j ChatMessage 列表
     */
    public List<ChatMessage> getRecentChatMessages(String conversationId, int maxMessages) {
        List<ConversationMessage> recentMessages = messageMapper.selectRecentMessages(conversationId, maxMessages);
        List<ChatMessage> messages = new ArrayList<>();
        // selectRecentMessages 按 create_time DESC 返回，需要反转顺序
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = recentMessages.get(i);
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            ChatMessage chatMsg = toChatMessage(msg);
            if (chatMsg != null) {
                messages.add(chatMsg);
            }
        }
        return messages;
    }

    /**
     * 从对话历史中提取用户最近表达的学习目标
     * 向上追溯最近的用户消息，排除简单确认词（如"好的"/"可以"等）
     *
     * @param conversationId 会话 ID
     * @return 最近的非确认用户消息；如果没有找到则返回 null
     */
    public String extractRecentGoal(String conversationId) {
        List<ConversationMessage> recentMessages = messageMapper.selectRecentMessages(conversationId, 20);

        // selectRecentMessages 按 create_time DESC 返回，从最近开始遍历
        for (ConversationMessage msg : recentMessages) {
            if (!"user".equals(msg.getRole())) continue;
            String content = msg.getContent();
            if (content == null || content.isBlank()) continue;

            // 跳过确认类短消息
            if (isConfirmationOnly(content)) continue;

            log.info("[MemoryService] 提取到用户原始学习目标: conversationId={}, goal={}",
                    conversationId, content.substring(0, Math.min(50, content.length())));
            return content;
        }

        log.warn("[MemoryService] 未从对话历史中找到学习目标: conversationId={}", conversationId);
        return null;
    }

    /**
     * 判断消息是否为仅包含确认表达的短消息（委托给 ConfirmationWords 统一管理）
     */
    private boolean isConfirmationOnly(String content) {
        return ConfirmationWords.isConfirmationOnly(content);
    }
}
