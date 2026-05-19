package com.smartorder.edu.agent.memory;

import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.entity.ConversationSummary;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.mapper.ConversationSummaryMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话压缩服务——消息超过 40 条时自动触发摘要压缩
 *
 * 压缩策略：
 * - 消息数 > 40 → 异步触发摘要
 * - 摘要写入 conversation_summary
 * - 旧消息标记 is_compressed = 1
 * - 后续 LLM 调用使用 摘要 + 最近 30 条
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationCompressService {

    private static final int COMPRESS_THRESHOLD = 40;

    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final ConversationMemoryService memoryService;
    private final ChatModel chatLanguageModel;

    /**
     * 检查并触发压缩（异步执行，不阻塞聊天）
     *
     * 增量摘要策略：旧摘要 + 新增未压缩消息 → 增量摘要，避免多轮压缩后早期信息丢失
     */
    @Async
    public void checkAndCompress(String conversationId, Long userId) {
        int count = memoryService.countUncompressed(conversationId);
        if (count <= COMPRESS_THRESHOLD) {
            return;
        }

        log.info("[Compress] 开始压缩: conversationId={}, messageCount={}", conversationId, count);

        try {
            // 1. 获取已有摘要
            ConversationSummary existing = summaryMapper.selectByConversationId(conversationId);
            String existingSummary = (existing != null && existing.getSummaryText() != null)
                    ? existing.getSummaryText() : "";

            // 2. 获取新增未压缩消息的文本
            String newMessagesText = memoryService.getUncompressedText(conversationId);
            if (newMessagesText.isBlank() && existingSummary.isBlank()) {
                log.warn("[Compress] 无可压缩内容: conversationId={}", conversationId);
                return;
            }

            // 3. 组合输入：旧摘要 + 新增消息
            String compressInput;
            if (existingSummary.isBlank()) {
                compressInput = newMessagesText;
            } else {
                compressInput = "【已有摘要】\n" + existingSummary + "\n\n【新增对话】\n" + newMessagesText;
            }

            // 4. 使用 SummaryAgent 生成增量摘要
            SummaryAgent summaryAgent = AiServices.builder(SummaryAgent.class)
                    .chatModel(chatLanguageModel)
                    .build();

            String summaryText = summaryAgent.summarize(compressInput);

            // 5. 保存或更新摘要
            if (existing != null) {
                existing.setSummaryText(summaryText);
                existing.setMessageCount(count);
                summaryMapper.updateById(existing);
            } else {
                ConversationSummary summary = new ConversationSummary();
                summary.setConversationId(conversationId);
                summary.setUserId(userId);
                summary.setSummaryText(summaryText);
                summary.setMessageCount(count);
                summary.setIsDeleted(0);
                summaryMapper.insert(summary);
            }

            // 6. 标记旧消息为已压缩（保留最近 30 条不压缩）
            List<ConversationMessage> recentMessages =
                    messageMapper.selectRecentMessages(conversationId, 30);
            // recentMessages 按 create_time DESC 排序，最后一条是最近 30 条中最旧的
            if (recentMessages != null && recentMessages.size() >= 30) {
                Long oldestRecentId = recentMessages.get(recentMessages.size() - 1).getId();
                // 标记 ID < oldestRecentId 的消息为已压缩
                messageMapper.markCompressed(conversationId, oldestRecentId);
                log.info("[Compress] 标记 {} 之前的消息为已压缩: conversationId={}", oldestRecentId, conversationId);
            }

            log.info("[Compress] 压缩完成: conversationId={}, summaryLength={}", conversationId, summaryText.length());

        } catch (Exception e) {
            log.error("[Compress] 压缩失败: conversationId={}", conversationId, e);
        }
    }
}
