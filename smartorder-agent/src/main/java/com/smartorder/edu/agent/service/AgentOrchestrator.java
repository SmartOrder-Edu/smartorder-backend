package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 编排入口。
 *
 * <p>生成任务模式下 {@code emitter} 传 null：所有输出经由生成事件日志，
 * 连接由订阅端管理；{@code generationId} 为空表示旧入口（计划确认等）。</p>
 *
 * <p>{@code turn} 是发送接口同步完成事务 A 之后拿到的会话/消息 ID。
 * 带上它以后，编排线程不再重复插入消息，也不会在异步阶段才产生 conversationId。</p>
 */
public interface AgentOrchestrator {

    /**
     * 处理一次 Agent 对话请求（旧入口：本轮事务由编排器自己开启）。
     */
    default void handle(AgentChatRequest request, SseEmitter emitter) {
        handle(request, emitter, null, null);
    }

    /**
     * 处理一次 Agent 对话请求（兼容调用方未预先落库消息的情况）。
     */
    default void handle(AgentChatRequest request, SseEmitter emitter, String generationId) {
        handle(request, emitter, generationId, null);
    }

    /**
     * 处理一次 Agent 对话请求。
     *
     * @param request      请求
     * @param emitter      旧入口的直接连接；生成任务模式传 null
     * @param generationId 生成任务 ID；为空表示旧入口
     * @param turn         已落库的本轮消息（会话/用户消息/assistant 占位行）；
     *                     为空时由编排器自行 openReply
     */
    void handle(AgentChatRequest request, SseEmitter emitter, String generationId, AgentTurnContext turn);
}
