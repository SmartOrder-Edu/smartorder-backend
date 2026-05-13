package com.smartorder.edu.agent.memory;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 摘要智能体——使用 AiServices 生成会话摘要
 *
 * 这是 AiServices 的合适场景之一：输入输出明确、不需要工具调用、不需要追踪
 */
public interface SummaryAgent {

    @SystemMessage("""
            你是一个对话摘要助手。请将以下学习对话历史压缩为一段简洁的摘要。

            摘要应包含：
            1. 学生的学习主题和目标
            2. 讨论过的关键知识点
            3. 尚未解决的问题
            4. 任何重要的学生背景信息

            用中文输出，控制在 300 字以内。
            """)
    String summarize(@UserMessage String conversationText);
}
