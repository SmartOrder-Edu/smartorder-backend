package com.smartorder.edu.agent.memory;

import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.entity.ConversationSummary;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.mapper.ConversationSummaryMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆服务——替代 AgentConfiguration 中的纯内存 ChatMemory
 *
 * 上下文组装策略：
 * System Prompt + 会话摘要 + 最近 30 条消息 + 当前用户消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;

    private static final int MAX_RECENT_MESSAGES = 30;

    private static final String SYSTEM_PROMPT = """
            你是「知序智学」的主学习 Agent，名为"知序"。你的职责是帮助大学生学习。

            ## 核心能力
            1. **了解学生**：通过工具了解学生的学习背景和进度
            2. **答疑解惑**：用清晰、易懂的方式解释知识点
            3. **制定计划**：根据学生情况，制定结构化的学习路径

            ## 行为准则
            - 在回答前，如果需要了解学生情况，先调用工具获取学生画像
            - 用热情、鼓励的语气交流
            - 回答尽量简洁，避免长篇大论
            - 对重要操作（如创建学习空间），必须明确告知用户
            - 不要编造学生档案信息，需要时必须调用工具
            """;

    /**
     * 构建发给 LLM 的消息列表
     */
    public List<ChatMessage> buildMessages(String conversationId, Long userId, String currentMessage) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System Prompt
        messages.add(SystemMessage.from(SYSTEM_PROMPT));

        // 2. 会话摘要（如果存在）
        ConversationSummary summary = summaryMapper.selectByConversationId(conversationId);
        if (summary != null && summary.getSummaryText() != null && !summary.getSummaryText().isBlank()) {
            messages.add(SystemMessage.from("【历史会话摘要】" + summary.getSummaryText()));
        }

        // 3. 最近 30 条消息
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

        // 4. 当前用户消息
        messages.add(UserMessage.from(currentMessage));

        log.info("[MemoryService] 构建上下文: conversationId={}, summary={}, recentMsgs={}, totalMsgs={}",
                conversationId,
                summary != null ? "有" : "无",
                recentMessages.size(),
                messages.size());

        return messages;
    }

    /**
     * 保存用户消息
     */
    public void saveUserMessage(String conversationId, Long userId, String content) {
        save(conversationId, userId, "user", content, null);
    }

    /**
     * 保存助手消息
     */
    public void saveAssistantMessage(String conversationId, Long userId, String content) {
        save(conversationId, userId, "assistant", content, null);
    }

    /**
     * 保存工具消息
     */
    public void saveToolMessage(String conversationId, Long userId, String toolName, String content) {
        save(conversationId, userId, "tool", content, toolName);
    }

    /**
     * 获取未压缩消息数量
     */
    public int countUncompressed(String conversationId) {
        return messageMapper.countUncompressed(conversationId);
    }

    // ============ Private ============

    private void save(String conversationId, Long userId, String role, String content, String toolName) {
        ConversationMessage entity = new ConversationMessage();
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setToolName(toolName);
        entity.setIsCompressed(0);
        entity.setIsDeleted(0);
        messageMapper.insert(entity);
    }

    private ChatMessage toChatMessage(ConversationMessage msg) {
        if (msg.getContent() == null) return null;
        switch (msg.getRole()) {
            case "user":
                return UserMessage.from(msg.getContent());
            case "assistant":
                return AiMessage.from(msg.getContent());
            case "system":
                return SystemMessage.from(msg.getContent());
            // tool 消息暂不加载到上下文
            default:
                return null;
        }
    }

    /**
     * 获取可用于生成摘要的对话文本
     */
    public String getConversationText(String conversationId) {
        List<ConversationMessage> messages = messageMapper.selectRecentMessages(conversationId, 50);
        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            sb.append("[").append(msg.getRole()).append("]: ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
