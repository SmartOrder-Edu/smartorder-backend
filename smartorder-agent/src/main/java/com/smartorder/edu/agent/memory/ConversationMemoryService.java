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

import com.smartorder.edu.agent.route.ConfirmationWords;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆服务——替代 AgentConfiguration 中的纯内存 ChatMemory
 *
 * 上下文组装策略：
 * System Prompt + 会话摘要 + 最近 30 条消息（含当前用户消息，因为 orchestrator 已提前落库）
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

            ## 核心能力（工具）
            你拥有以下工具：
            - **getStudentProfile**：获取学生的学习画像
            - **manageLearningTask**：为用户创建学习规划草稿（需用户确认后才会创建学习空间和任务）
            - **queryKnowledge**：查询知识图谱
            - **queryTasks**：查询学习任务
            - **queryLearningProgress**：查询学习进度
            - **generateQuiz**：生成练习题和测试
            - **generateResource**：生成学习资料

            ## 行为准则
            - 在回答前，如果需要了解学生情况，先调用工具获取学生画像
            - 用热情、鼓励的语气交流
            - 回答尽量简洁，避免长篇大论
            - 不要编造学生档案信息，需要时必须调用工具
            - **当用户表达学习目标时，先调用 getStudentProfile 了解学生情况，然后调用 manageLearningTask 生成规划草稿**
            - **manageLearningTask 只会生成草稿，不会直接创建学习空间。草稿生成后，需要用户确认才会创建学习空间和任务**
            - **告知用户规划内容后，询问"是否确认创建？"，等待用户确认后再引导用户确认**
            """;

    /**
     * 构建发给 LLM 的历史上下文消息列表（包含当前用户消息）
     *
     * 注意：AgentOrchestratorImpl 在调用引擎前已将当前用户消息保存到 DB，
     * 因此 selectRecentMessages 会包含当前用户消息，调用方不应再手动追加。
     */
    public List<ChatMessage> buildMessages(String conversationId, Long userId) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System Prompt
        messages.add(SystemMessage.from(SYSTEM_PROMPT));

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
     * 保存用户消息
     */
    public void saveUserMessage(String conversationId, Long userId, String content) {
        save(conversationId, userId, "user", content, null, null);
    }

    /**
     * 保存助手消息（含 reasoning_content，thinking mode 必须回传给 API）
     */
    public void saveAssistantMessage(String conversationId, Long userId, String content, String reasoningContent) {
        save(conversationId, userId, "assistant", content, null, reasoningContent);
    }

    /**
     * 保存工具消息
     */
    public void saveToolMessage(String conversationId, Long userId, String toolName, String content) {
        save(conversationId, userId, "tool", content, toolName, null);
    }

    /**
     * 获取未压缩消息数量
     */
    public int countUncompressed(String conversationId) {
        return messageMapper.countUncompressed(conversationId);
    }

    // ============ Private ============

    private void save(String conversationId, Long userId, String role, String content, String toolName, String reasoningContent) {
        ConversationMessage entity = new ConversationMessage();
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setToolName(toolName);
        entity.setReasoningContent(reasoningContent);
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
