package com.smartorder.edu.student.domain.mq;

import java.io.Serializable;

/**
 * 代码题沙箱判题消息体
 *
 * @param recordId   答题记录主键
 * @param questionId 题目 ID
 * @param sessionId  测评会话 ID
 * @param userId     用户 ID
 * @param language   编程语言
 * @param sourceCode 用户提交的源码
 * @author SmartOrder
 * @since 2026-07-11
 */
public record CodeGradingMessage(
        Long recordId,
        Long questionId,
        Long sessionId,
        Long userId,
        String language,
        String sourceCode
) implements Serializable {
}
