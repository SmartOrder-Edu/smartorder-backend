package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 学生画像候选信息提取器接口
 */
public interface ProfileUpdateCandidateExtractor {

    /**
     * 从当前用户消息中提取学生画像候选更新
     *
     * @param currentUserMessage  当前用户消息
     * @param conversationHistory 对话历史消息列表（供 LLM 参考上下文），可为空
     */
    UpdateStudentProfileRequest extract(String currentUserMessage, List<ChatMessage> conversationHistory);
}
