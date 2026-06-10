package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.plan.PlanExecuteEngine;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.route.IntentRouteService;
import com.smartorder.edu.agent.route.RouteContext;
import com.smartorder.edu.agent.service.AgentConversationAttachmentService;
import com.smartorder.edu.agent.service.AttachmentNotReadyException;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 编排器实现——编排主流程
 *
 * 阶段五：REACT + PLAN_EXECUTE + MULTI_AGENT 全部接入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private final IntentRouteService intentRouteService;
    private final SelfManagedReActEngine reActEngine;
    private final PlanExecuteEngine planExecuteEngine;
    private final MultiAgentCoordinator multiAgentCoordinator;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;
    private final AgentConversationAttachmentService attachmentService;

    @Override
    public void handle(AgentChatRequest request, SseEmitter emitter) {
        String conversationId = resolveConversationId(request);

        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .userId(request.getUserId())
                .build();

        try {
            // 0. 统一保存用户消息（所有模式都落库，含附件关联）
            memoryService.saveUserMessage(conversationId, request.getUserId(), request.getMessage(),
                    request.getFileIds());
            request.setConversationId(conversationId);

            if (request.getFileIds() != null && !request.getFileIds().isEmpty()) {
                try {
                    String attachmentContext = attachmentService.buildRagContext(
                            request.getUserId(), conversationId, request.getFileIds(), request.getMessage());
                    context.setAttachmentContext(attachmentContext);
                    dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                            "type", "attachment_rag",
                            "fileIds", request.getFileIds(),
                            "hit", attachmentContext != null && !attachmentContext.isBlank()
                    ));
                } catch (AttachmentNotReadyException e) {
                    String answer = e.getMessage();
                    dispatcher.emit(emitter, context, AgentEventType.TRACE_STARTED, Map.of(
                            "mode", context.getMode().getCode()
                    ));
                    dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                            "type", "attachment_status",
                            "message", answer
                    ));
                    dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                            "text", answer
                    ));
                    memoryService.saveAssistantMessage(conversationId, request.getUserId(), answer,
                            null, context.getTraceId(), null);
                    dispatcher.complete(emitter, context);
                    return;
                }
            }

            // 1. 意图路由（带会话上下文）
            RouteContext routeCtx = RouteContext.builder()
                    .message(request.getMessage())
                    .conversationId(conversationId)
                    .userId(request.getUserId())
                    .build();
            IntentRouteResult routeResult = intentRouteService.route(routeCtx);
            context.setMode(routeResult.getRunMode());

            // 2. 推送 TRACE_STARTED
            dispatcher.emit(emitter, context, AgentEventType.TRACE_STARTED, Map.of(
                    "mode", routeResult.getRunMode().getCode()
            ));

            // 3. 推送 ROUTE_COMPLETED（含新增字段）
            dispatcher.emit(emitter, context, AgentEventType.ROUTE_COMPLETED, Map.of(
                    "intentType", routeResult.getIntentType(),
                    "intentEnum", routeResult.getIntentEnum() != null ? routeResult.getIntentEnum().name() : "",
                    "complexity", routeResult.getComplexity(),
                    "runMode", routeResult.getRunMode().getCode(),
                    "requiredTools", routeResult.getRequiredTools(),
                    "confidence", routeResult.getConfidence(),
                    "source", routeResult.getSource().name(),
                    "needsClarification", routeResult.isNeedsClarification(),
                    "reason", routeResult.getReason()
            ));

            // 4. 按运行模式分发
            switch (routeResult.getRunMode()) {
                case REACT -> reActEngine.execute(request, routeResult, context, emitter);
                case PLAN_EXECUTE -> handlePlanExecute(request, routeResult, context, emitter);
                case MULTI_AGENT -> handleMultiAgent(request, routeResult, context, emitter);
                default -> throw new IllegalStateException("不支持的运行模式: " + routeResult.getRunMode());
            }

            // 5. 完成
            dispatcher.complete(emitter, context);

        } catch (Exception e) {
            log.error("[AgentOrchestrator] 执行失败: traceId={}", context.getTraceId(), e);
            dispatcher.error(emitter, context, e);
        }
    }

    // ============ 模式处理（PLAN_EXECUTE / MULTI_AGENT 后续阶段完善） ============

    private void handlePlanExecute(AgentChatRequest request, IntentRouteResult routeResult,
                                   AgentRuntimeContext context, SseEmitter emitter) {
        planExecuteEngine.runPlanning(request, routeResult, context, emitter);
    }

    private void handleMultiAgent(AgentChatRequest request, IntentRouteResult routeResult,
                                  AgentRuntimeContext context, SseEmitter emitter) {
        multiAgentCoordinator.execute(request, routeResult, context, emitter);
    }

    // ============ Private Methods ============

    private String resolveConversationId(AgentChatRequest request) {
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            return request.getConversationId();
        }
        return UUID.randomUUID().toString();
    }
}
