package com.smartorder.edu.agent.route;

import lombok.Builder;
import lombok.Data;

/**
 * 路由输入上下文——包含消息、会话、用户三维信息
 */
@Data
@Builder
public class RouteContext {

    /** 用户输入消息 */
    private String message;

    /** 会话 ID（用于状态检查和上下文追溯） */
    private String conversationId;

    /** 用户 ID */
    private Long userId;
}
