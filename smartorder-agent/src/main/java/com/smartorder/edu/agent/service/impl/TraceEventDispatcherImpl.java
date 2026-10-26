package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTraceEventDTO;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;
import com.smartorder.edu.agent.runtime.AgentTaskRegistry;
import com.smartorder.edu.agent.service.TraceEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 追踪事件分发器实现
 *
 * <p>生成任务模式：事件交给 {@link AgentTaskRegistry}，由生成对象分配 seq、
 * 写入回放日志并推给所有订阅者；本类不再直接持有连接，因此连接断开不影响生成。</p>
 *
 * <p>旧入口模式（计划确认/澄清/确认门）：保持原有行为，直接写当前 SseEmitter，
 * 发送失败时标记 clientDisconnected（引擎会据此提前退出）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceEventDispatcherImpl implements TraceEventDispatcher {

    private final TraceEventAsyncPersister asyncPersister;

    private final AgentTaskRegistry taskRegistry;

    @Override
    public void emit(SseEmitter emitter, AgentRuntimeContext context, AgentEventType eventType, Object payload) {
        AgentTaskEvent taskEvent = taskRegistry.publish(context, eventType, payload);
        if (taskEvent != null) {
            // 生成任务模式：只负责归档到 trace 表（审计），推送已由生成对象完成
            if (eventType.isPersistable()) {
                asyncPersister.persistAsync(toDto(context, eventType, (int) taskEvent.getSeq(), payload));
            }
            return;
        }
        if (context.getTaskId() != null) {
            // 生成已终态：异步迟到的事件（如标题）不能再进回放日志，丢弃即可
            log.debug("[TraceDispatcher] 生成已结束，丢弃迟到事件: taskId={}, event={}",
                    context.getTaskId(), eventType.getEventName());
            return;
        }
        emitLegacy(emitter, context, eventType, payload);
    }

    @Override
    public void emitEphemeral(SseEmitter emitter, AgentRuntimeContext context,
                              AgentEventType eventType, Object payload) {
        if (context.getTaskId() != null) {
            taskRegistry.publishEphemeral(context, eventType.getEventName(), payload);
            return;
        }
        emitLegacy(emitter, context, eventType, payload);
    }

    @Override
    public void complete(SseEmitter emitter, AgentRuntimeContext context) {
        Map<String, Object> traceCompletedPayload = new LinkedHashMap<>();
        traceCompletedPayload.put("conversationId", context.getConversationId());
        emit(emitter, context, AgentEventType.TRACE_COMPLETED, traceCompletedPayload);

        Map<String, Object> donePayload = buildDonePayload(context);

        if (context.getTaskId() != null) {
            AgentTaskStatus terminalStatus = taskRegistry.isCancelRequested(context)
                    ? AgentTaskStatus.CANCELLED
                    : AgentTaskStatus.COMPLETED;
            taskRegistry.finish(context, donePayload, terminalStatus, null);
            return;
        }

        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(AgentEventType.DONE.getEventName())
                    .data(JSON.toJSONString(donePayload)));
        } catch (IOException | IllegalStateException e) {
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

        Map<String, Object> donePayload = buildDonePayload(context);
        donePayload.put("error", errorMsg);

        if (context.getTaskId() != null) {
            taskRegistry.finish(context, donePayload, AgentTaskStatus.FAILED, errorMsg);
            return;
        }

        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(AgentEventType.DONE.getEventName())
                    .data(JSON.toJSONString(donePayload)));
        } catch (IOException | IllegalStateException ex) {
            log.warn("[TraceDispatcher] error done 发送失败");
        }
        emitter.complete();
    }

    // ============ 内部 ============

    /**
     * 旧入口：直接写连接。发送失败记录 clientDisconnected（仅旧入口会据其提前退出）。
     */
    private void emitLegacy(SseEmitter emitter, AgentRuntimeContext context,
                            AgentEventType eventType, Object payload) {
        AgentTraceEventDTO event = toDto(context, eventType, context.nextSeq(), payload);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType.getEventName())
                        .data(JSON.toJSONString(event)));
            } catch (IOException | IllegalStateException e) {
                log.warn("[TraceDispatcher] SSE 发送失败: traceId={}, event={}",
                        context.getTraceId(), eventType.getEventName());
                context.setClientDisconnected(true);
            }
        }
        if (eventType.isPersistable()) {
            asyncPersister.persistAsync(event);
        }
    }

    private AgentTraceEventDTO toDto(AgentRuntimeContext context, AgentEventType eventType,
                                     int seq, Object payload) {
        return AgentTraceEventDTO.builder()
                .taskId(context.getTaskId())
                .traceId(context.getTraceId())
                .conversationId(context.getConversationId())
                .userId(context.getUserId())
                .mode(context.getMode() != null ? context.getMode().getCode() : null)
                .eventType(eventType.getEventName())
                .seq(seq)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
    }

    /**
     * done 事件保持“扁平结构”（不带事件信封），兼容历史前端：
     * 前端直接从 done.data 读取 messageId / runStatus / clarificationPayload。
     */
    private Map<String, Object> buildDonePayload(AgentRuntimeContext context) {
        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("conversationId", context.getConversationId());
        donePayload.put("traceId", context.getTraceId());
        if (context.getAssistantMessageId() != null) {
            donePayload.put("messageId", context.getAssistantMessageId());
        }
        if (context.getAssistantSequenceNo() != null) {
            donePayload.put("sequenceNo", context.getAssistantSequenceNo());
        }
        if (context.getPlanRunStatus() != null) {
            donePayload.put("runStatus", context.getPlanRunStatus());
        }
        if (context.getPlanDraftId() != null) {
            donePayload.put("draftId", context.getPlanDraftId());
        }
        if (context.getActiveClarificationId() != null) {
            donePayload.put("activeClarificationId", context.getActiveClarificationId());
        }
        if (context.getClarificationPayload() != null) {
            donePayload.put("clarificationPayload", context.getClarificationPayload());
        }
        return donePayload;
    }
}
