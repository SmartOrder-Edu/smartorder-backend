package com.smartorder.edu.agent.memory;

/**
 * 会话标题智能体——把用户首条消息压缩成短标题。
 *
 * <p>与 {@link SummaryAgent} 同属"输入输出明确、不需要工具调用"的轻量 AiServices 场景。
 * SystemMessage 在构建时通过
 * {@code .systemMessageProvider(memoryId -> promptLoader.getTitleGeneratorPrompt())} 注入。</p>
 */
public interface TitleGeneratorAgent {

    String generateTitle(String userMessage);
}
