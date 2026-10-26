package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 追踪事件分发器——统一事件出口。
 *
 * <p>有生成上下文（{@code context.generationId} 非空）时，事件先写入生成事件日志再推送，
 * 支持按 seq 回放；没有生成上下文时（计划确认等旧入口），保持原来的“直接写 SSE 连接”。</p>
 */
public interface TraceEventDispatcher {

    /**
     * 推送一个追踪事件到 SSE 连接，并异步持久化到 agent_trace_event 表
     */
    void emit(SseEmitter emitter, AgentRuntimeContext context, AgentEventType eventType, Object payload);

    /**
     * 推送“只给在线连接、不进入回放日志”的即时事件（如异步生成的会话标题）。
     * <p>这类事件发生在 done 之后或与主流程无关，不能进入回放游标，否则会破坏终态语义。</p>
     */
    void emitEphemeral(SseEmitter emitter, AgentRuntimeContext context, AgentEventType eventType, Object payload);

    /**
     * 推送追踪完成 + done 事件，关闭 SSE 连接
     */
    void complete(SseEmitter emitter, AgentRuntimeContext context);

    /**
     * 推送错误事件并关闭 SSE 连接
     */
    void error(SseEmitter emitter, AgentRuntimeContext context, Exception e);
}
