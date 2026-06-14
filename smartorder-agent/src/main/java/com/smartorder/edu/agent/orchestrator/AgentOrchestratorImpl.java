package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.rag.model.RagQueryRequest;
import com.smartorder.edu.agent.rag.model.RagQueryResponse;
import com.smartorder.edu.agent.rag.orchestrator.RagOrchestrator;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.route.IntentRouteService;
import com.smartorder.edu.agent.route.IntentType;
import com.smartorder.edu.agent.route.RouteContext;
import com.smartorder.edu.agent.route.RoutingSource;
import com.smartorder.edu.agent.service.AgentConversationAttachmentService;
import com.smartorder.edu.agent.service.AttachmentNotReadyException;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 编排器实现——编排主流程
 * <p>
 * 两种运行模式：PureReAct（简单问答）+ PlanAndExecute（复杂任务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private final IntentRouteService intentRouteService;
    private final SelfManagedReActEngine reActEngine;
    private final PlanAndExecuteEngine planAndExecuteEngine;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;
    private final AgentConversationAttachmentService attachmentService;
    private final RagOrchestrator ragOrchestrator;

    @Override
    public void handle(AgentChatRequest request, SseEmitter emitter) {
        String conversationId = resolveConversationId(request);

        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .userId(request.getUserId())
                .message(request.getMessage())
                .knowledgeBaseIds(request.getKnowledgeBaseIds())
                .fileIds(request.getFileIds())
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
                    context.setAllowedAttachmentFileIds(normalizeFileIds(request.getFileIds()));
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

//            // 0.5 自动触发 RAG 检索（当提供了 fileIds 或 knowledgeBaseIds 时）
//            if (shouldAutoTriggerRag(request)) {
//                try {
//                    RagQueryRequest ragRequest = RagQueryRequest.builder()
//                            .userId(request.getUserId())
//                            .query(request.getMessage())
//                            .fileIds(request.getFileIds())
//                            .knowledgeBaseIds(request.getKnowledgeBaseIds())
//                            .topK(10)
//                            .build();
//                    RagQueryResponse ragResponse = ragOrchestrator.retrieve(ragRequest);
//                    if (ragResponse.isSuccess() && ragResponse.getContextText() != null
//                            && !ragResponse.getContextText().isBlank()) {
//                        context.setRagContext(ragResponse.getContextText());
//                        log.info("[AgentOrchestrator] 自动 RAG 检索成功，chunkCount={}",
//                                ragResponse.getChunks() != null ? ragResponse.getChunks().size() : 0);
//                    }
//                    dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
//                            "type", "auto_rag",
//                            "fileIds", request.getFileIds() != null ? request.getFileIds() : List.of(),
//                            "knowledgeBaseIds", request.getKnowledgeBaseIds() != null ? request.getKnowledgeBaseIds() : List.of(),
//                            "hit", ragResponse.isSuccess()
//                    ));
//                } catch (Exception e) {
//                    log.warn("[AgentOrchestrator] 自动 RAG 检索失败，继续执行", e);
//                }
//            }

            // 0.5 显式模式短路：跳过意图路由，直接按用户选择的模式执行
            if (request.getForceMode() != null && !request.getForceMode().isBlank()) {
                handleForcedMode(request, emitter, context, conversationId);
                dispatcher.complete(emitter, context);
                return;
            }

            // 1. 意图路由（带会话上下文）
            RouteContext routeCtx = RouteContext.builder()
                    .message(request.getMessage())
                    .conversationId(conversationId)
                    .userId(request.getUserId())
                    .build();
            IntentRouteResult routeResult = intentRouteService.route(routeCtx);
            context.setMode(routeResult.getRunMode());
            addAttachmentSearchToolIfNeeded(routeResult, context);
            addRagToolIfNeeded(routeResult, context);

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
                    "needsPlanning", routeResult.isNeedsPlanning(),
                    "reason", routeResult.getReason()
            ));

            // 4. 按运行模式分发
            switch (routeResult.getRunMode()) {
                case REACT -> {
                    if (routeResult.isNeedsClarification()) {
                        // 澄清短路：直接输出澄清文本，不走 ReAct
                        String clarificationMsg = routeResult.getReason();
                        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                                "text", clarificationMsg
                        ));
                        memoryService.saveAssistantMessage(conversationId, request.getUserId(),
                                clarificationMsg, null, context.getTraceId(), null);
                    } else {
                        reActEngine.execute(request, routeResult, context, emitter);
                    }
                }
                case PLAN_AND_EXECUTE -> handlePlanAndExecute(request, routeResult, context, emitter);
                default -> throw new IllegalStateException("不支持的运行模式: " + routeResult.getRunMode());
            }

            // 5. 完成
            dispatcher.complete(emitter, context);

        } catch (Exception e) {
            log.error("[AgentOrchestrator] 执行失败: traceId={}", context.getTraceId(), e);
            dispatcher.error(emitter, context, e);
        }
    }

    // ============ PlanAndExecute 分发 ============

    private void handlePlanAndExecute(AgentChatRequest request, IntentRouteResult routeResult,
                                       AgentRuntimeContext context, SseEmitter emitter) {
        if (routeResult.isNeedsPlanning()) {
            // 首次进入：生成 ExecutionPlan
            planAndExecuteEngine.runPlanning(request, routeResult, context, emitter);
        } else {
            // 恢复执行：StateRuleRouter 检测到暂停的计划
            planAndExecuteEngine.resumePlan(request, context, emitter);
        }
    }

    // ============ Private Methods ============

    private String resolveConversationId(AgentChatRequest request) {
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            return request.getConversationId();
        }
        return UUID.randomUUID().toString();
    }

    private void addAttachmentSearchToolIfNeeded(IntentRouteResult routeResult, AgentRuntimeContext context) {
        if (routeResult.getRunMode() != AgentRunMode.REACT) {
            return;
        }
        List<Long> fileIds = context.getAllowedAttachmentFileIds();
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }

        List<String> currentTools = routeResult.getRequiredTools() != null
                ? routeResult.getRequiredTools() : List.of();
        if (currentTools.contains("searchConversationAttachments")) {
            return;
        }

        List<String> tools = new ArrayList<>(currentTools);
        tools.add("searchConversationAttachments");
        routeResult.setRequiredTools(tools);
    }

    /**
     * 当用户指定了知识库ID或文件ID时，注入 ragRetrieve 工具供 LLM 自主调用
     */
    private void addRagToolIfNeeded(IntentRouteResult routeResult, AgentRuntimeContext context) {
        if (routeResult.getRunMode() != AgentRunMode.REACT) {
            return;
        }
        boolean hasKnowledgeBases = context.getKnowledgeBaseIds() != null
                && !context.getKnowledgeBaseIds().isEmpty();
        boolean hasFiles = context.getFileIds() != null
                && !context.getFileIds().isEmpty();
        if (!hasKnowledgeBases && !hasFiles) {
            return;
        }

        List<String> currentTools = routeResult.getRequiredTools() != null
                ? routeResult.getRequiredTools() : List.of();
        if (currentTools.contains("ragRetrieve")) {
            return;
        }

        List<String> tools = new ArrayList<>(currentTools);
        tools.add("ragRetrieve");
        routeResult.setRequiredTools(tools);
        log.info("[AgentOrchestrator] 注入 ragRetrieve 工具，knowledgeBaseIds={}, fileIds={}",
                context.getKnowledgeBaseIds(), context.getFileIds());
    }

    // ============ 显式模式短路 ============

    /**
     * 用户手动选择模式时，跳过意图路由，直接执行
     */
    private void handleForcedMode(AgentChatRequest request, SseEmitter emitter,
                                  AgentRuntimeContext context, String conversationId) {
        AgentRunMode mode;
        try {
            mode = AgentRunMode.valueOf(request.getForceMode().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[AgentOrchestrator] 无效的 forceMode: {}, 回退到 REACT", request.getForceMode());
            mode = AgentRunMode.REACT;
        }

        IntentRouteResult forcedResult = buildForcedRouteResult(mode);
        context.setMode(forcedResult.getRunMode());
        addAttachmentSearchToolIfNeeded(forcedResult, context);
        addRagToolIfNeeded(forcedResult, context);

        // 推送 TRACE_STARTED
        dispatcher.emit(emitter, context, AgentEventType.TRACE_STARTED, Map.of(
                "mode", forcedResult.getRunMode().getCode()
        ));

        // 推送 ROUTE_COMPLETED
        dispatcher.emit(emitter, context, AgentEventType.ROUTE_COMPLETED, Map.of(
                "intentType", forcedResult.getIntentType(),
                "intentEnum", forcedResult.getIntentEnum() != null ? forcedResult.getIntentEnum().name() : "",
                "complexity", forcedResult.getComplexity(),
                "runMode", forcedResult.getRunMode().getCode(),
                "requiredTools", forcedResult.getRequiredTools(),
                "confidence", forcedResult.getConfidence(),
                "source", forcedResult.getSource().name(),
                "needsClarification", forcedResult.isNeedsClarification(),
                "needsPlanning", forcedResult.isNeedsPlanning(),
                "reason", forcedResult.getReason()
        ));

        // 按模式分发
        switch (forcedResult.getRunMode()) {
            case REACT -> reActEngine.execute(request, forcedResult, context, emitter);
            case PLAN_AND_EXECUTE -> handlePlanAndExecute(request, forcedResult, context, emitter);
            default -> throw new IllegalStateException("不支持的运行模式: " + forcedResult.getRunMode());
        }
    }

    private IntentRouteResult buildForcedRouteResult(AgentRunMode mode) {
        boolean needsPlanning = mode == AgentRunMode.PLAN_AND_EXECUTE;
        List<String> tools = needsPlanning
                ? List.of()
                : List.of("getStudentProfile", "queryKnowledge", "queryTasks");
        return IntentRouteResult.builder()
                .intentType(needsPlanning ? "目标规划（手动选择）" : "知识问答（手动选择）")
                .intentEnum(needsPlanning ? IntentType.GOAL_PLANNING : IntentType.KNOWLEDGE_QA)
                .complexity(needsPlanning ? "复杂" : "简单")
                .runMode(mode)
                .requiredTools(tools)
                .confidence(1.0)
                .source(RoutingSource.USER_SELECTED)
                .reason("用户手动选择模式")
                .needsPlanning(needsPlanning)
                .planHint(needsPlanning ? "goal_planning" : null)
                .build();
    }

    private List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(fileIds));
    }

    /**
     * 判断是否需要自动触发 RAG 检索
     */
    private boolean shouldAutoTriggerRag(AgentChatRequest request) {
        boolean hasKnowledgeBases = request.getKnowledgeBaseIds() != null
                && !request.getKnowledgeBaseIds().isEmpty();
        boolean hasFiles = request.getFileIds() != null
                && !request.getFileIds().isEmpty();
        return hasKnowledgeBases || hasFiles;
    }
}
