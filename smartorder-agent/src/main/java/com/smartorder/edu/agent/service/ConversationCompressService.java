package com.smartorder.edu.agent.service;


/**
 * 会话压缩服务——消息超过 40 条时自动触发摘要压缩
 *
 * 压缩策略：
 * - 消息数 > 40 → 异步触发摘要
 * - 摘要写入 conversation_summary
 * - 旧消息标记 is_compressed = 1
 * - 后续 LLM 调用使用 摘要 + 最近 30 条
 */
public interface ConversationCompressService {

    void checkAndCompress(String conversationId, Long userId);
}
