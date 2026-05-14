package com.smartorder.edu.agent.trace;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 追踪事件分发器——负责结构化 SSE 推送 + 异步写追踪日志
 */
public interface TraceEventDispatcher {

    /**
     * 推送一个追踪事件到 SSE 连接，并异步持久化到 agent_trace_event 表
     */
    void emit(SseEmitter emitter, AgentRuntimeContext context, AgentEventType eventType, Object payload);

    /**
     * 推送追踪完成 + done 事件，关闭 SSE 连接
     */
    void complete(SseEmitter emitter, AgentRuntimeContext context);

    /**
     * 推送错误事件并关闭 SSE 连接
     */
    void error(SseEmitter emitter, AgentRuntimeContext context, Exception e);
}
