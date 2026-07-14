package com.smartorder.edu.student.domain.mq;

import java.io.Serializable;

/**
 * 主观题 AI 判分消息体
 *
 * @param recordId   答题记录主键
 * @param questionId 题目 ID
 * @param sessionId  测评会话 ID
 * @param userId     用户 ID
 * @author SmartOrder
 * @since 2026-07-11
 */
public record SubjectiveGradingMessage(
        Long recordId,
        Long questionId,
        Long sessionId,
        Long userId
) implements Serializable {
}
