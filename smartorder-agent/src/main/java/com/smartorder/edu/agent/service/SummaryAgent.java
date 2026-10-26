package com.smartorder.edu.agent.service;


/**
 * 摘要智能体——使用 AiServices 生成会话摘要
 *
 * 这是 AiServices 的合适场景之一：输入输出明确、不需要工具调用、不需要追踪。
 * SystemMessage 在构建 AiServices 时通过
 * {@code .systemMessageProvider(memoryId -> promptLoader.getSummarizerSystemMessage())} 注入。
 */
public interface SummaryAgent {

    String summarize(String conversationText);
}
