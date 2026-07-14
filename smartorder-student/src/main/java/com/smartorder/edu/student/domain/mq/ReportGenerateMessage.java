package com.smartorder.edu.student.domain.mq;

import java.io.Serializable;

/**
 * 学习报告异步生成消息体
 *
 * @param sessionId 测评会话 ID
 * @param userId    用户 ID
 * @author SmartOrder
 * @since 2026-07-11
 */
public record ReportGenerateMessage(
        Long sessionId,
        Long userId
) implements Serializable {
}
