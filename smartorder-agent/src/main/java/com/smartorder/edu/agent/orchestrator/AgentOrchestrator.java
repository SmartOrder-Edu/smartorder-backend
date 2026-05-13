package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.dto.AgentChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 编排器接口——负责编排整个 Agent 调用链路
 */
public interface AgentOrchestrator {

    /**
     * 处理一次 Agent 对话请求，通过 SSE 返回结构化事件流
     */
    void handle(AgentChatRequest request, SseEmitter emitter);
}
