package com.smartorder.edu.agent.trace;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.route.AgentRunMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 追踪事件分发器实现——结构化 SSE 推送
 *
 * 第一阶段简化实现：只做 SSE 推送，暂不异步落库。
 * 数据库持久化在后续阶段补充。
 */
@Slf4j
@Component
public class TraceEventDispatcherImpl implements TraceEventDispatcher {

    @Override
    public void emit(SseEmitter emitter, AgentRuntimeContext context, AgentEventType eventType, Object payload) {
        if (emitter == null) return;

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

        try {
            emitter.send(SseEmitter.event()
                    .name(eventType.getEventName())
                    .data(JSON.toJSONString(event)));
        } catch (IOException e) {
            log.warn("[TraceDispatcher] SSE 发送失败: traceId={}, event={}", context.getTraceId(), eventType.getEventName());
        }
    }

    @Override
    public void complete(SseEmitter emitter, AgentRuntimeContext context) {
        if (emitter == null) return;

        // 推送 TRACE_COMPLETED
        emit(emitter, context, AgentEventType.TRACE_COMPLETED,
                Map.of("conversationId", context.getConversationId()));

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
