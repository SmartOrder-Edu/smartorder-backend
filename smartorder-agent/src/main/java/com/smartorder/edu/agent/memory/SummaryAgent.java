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
            你是一个对话摘要助手。请将以下内容压缩为一段简洁的增量摘要。

            输入可能包含：
            - 【已有摘要】：之前压缩的历史摘要
            - 【新增对话】：新增的未压缩对话消息

            你需要将已有摘要和新增对话合并为一段统一的摘要，保留所有重要信息。

            摘要应包含：
            1. 学生的学习主题和目标
            2. 讨论过的关键知识点
            3. 尚未解决的问题
            4. 任何重要的学生背景信息

            用中文输出，控制在 300 字以内。不要丢失已有摘要中的关键信息。
            """)
    String summarize(@UserMessage String conversationText);
}
