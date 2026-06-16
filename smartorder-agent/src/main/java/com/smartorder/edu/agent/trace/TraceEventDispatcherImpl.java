package com.smartorder.edu.agent.trace;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.route.AgentRunMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 追踪事件分发器实现
 *
 * 1. SSE 推送结构化事件
 * 2. 异步落库到 agent_trace_event 表（不阻塞 SSE）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceEventDispatcherImpl implements TraceEventDispatcher {

    private final TraceEventAsyncPersister asyncPersister;

    @Override
    public void emit(SseEmitter emitter, AgentRuntimeContext context, AgentEventType eventType, Object payload) {
        AgentTraceEventDTO event = AgentTraceEventDTO.builder()
                .traceId(context.getTraceId())
                .conversationId(context.getConversationId())
                .userId(context.getUserId())
                .mode(context.getMode() != null ? context.getMode().getCode() : null)
                .eventType(eventType.getEventName())
                .seq(context.nextSeq())
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();

        // 1. SSE 推送
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType.getEventName())
                        .data(JSON.toJSONString(event)));
            } catch (IOException e) {
                log.warn("[TraceDispatcher] SSE 发送失败: traceId={}, event={}", context.getTraceId(), eventType.getEventName());
                context.setClientDisconnected(true);
            }
        }

        // 2. 异步落库（高频 delta 事件不持久化，完整文本已存于 conversation_message）
        if (eventType.isPersistable()) {
            asyncPersister.persistAsync(event);
        }
    }

    @Override
    public void complete(SseEmitter emitter, AgentRuntimeContext context) {
        // 推送 TRACE_COMPLETED
        emit(emitter, context, AgentEventType.TRACE_COMPLETED,
                Map.of("conversationId", context.getConversationId()));

        if (emitter == null) return;

        // 推送 DONE
        try {
            emitter.send(SseEmitter.event()
                    .name(AgentEventType.DONE.getEventName())
                    .data(JSON.toJSONString(Map.of(
                            "conversationId", context.getConversationId(),
                            "traceId", context.getTraceId()
                    ))));
        } catch (IOException e) {
            log.warn("[TraceDispatcher] done 事件发送失败: traceId={}", context.getTraceId());
        }

        emitter.complete();
        log.info("[TraceDispatcher] 追踪完成: traceId={}, conversationId={}, totalEvents={}",
                context.getTraceId(), context.getConversationId(), context.currentSeq());
    }

    @Override
    public void error(SseEmitter emitter, AgentRuntimeContext context, Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.error("[TraceDispatcher] 追踪错误: traceId={}, error={}", context.getTraceId(), errorMsg, e);

        emit(emitter, context, AgentEventType.ERROR, Map.of("message", errorMsg));

        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name(AgentEventType.DONE.getEventName())
                    .data(JSON.toJSONString(Map.of(
                            "conversationId", context.getConversationId(),
                            "traceId", context.getTraceId(),
                            "error", errorMsg
                    ))));
        } catch (IOException ex) {
            log.warn("[TraceDispatcher] error done 发送失败");
        }
        emitter.completeWithError(e);
    }
}
