package com.smartorder.edu.agent.service;

import reactor.core.publisher.Flux;

/**
 * 主 Agent 服务接口
 */
public interface MasterAgentService {

    /**
     * 与 Agent 对话（流式返回）
     * @param userId 用户 ID
     * @param message 用户消息
     * @param conversationId 会话 ID（新会话传 null）
     * @return 流式字符串
     */
    Flux<String> chat(Long userId, String message, String conversationId);
}
