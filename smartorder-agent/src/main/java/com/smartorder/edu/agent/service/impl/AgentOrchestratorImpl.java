package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.exception.AttachmentNotReadyException;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.IntentRouteResult;
import com.smartorder.edu.agent.model.dto.RouteContext;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentRunMode;
import com.smartorder.edu.agent.model.enums.IntentType;
import com.smartorder.edu.agent.model.enums.RoutingSource;
import com.smartorder.edu.agent.runtime.AgentTaskRegistry;
import com.smartorder.edu.agent.runtime.ExecutionBudget;
import com.smartorder.edu.agent.service.AgentConversationAttachmentService;
import com.smartorder.edu.agent.service.AgentOrchestrator;
import com.smartorder.edu.agent.service.ConversationContextService;
import com.smartorder.edu.agent.service.ConversationTitleService;
import com.smartorder.edu.agent.service.ConversationMessageService;
import com.smartorder.edu.agent.service.IntentRouteService;
import com.smartorder.edu.agent.service.PlannerAgent;
import com.smartorder.edu.agent.service.TraceEventDispatcher;
import com.smartorder.edu.agent.service.impl.ProfileUpdateCoordinator;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 标题补生成的最大次数：前 3 条用户消息内，只要标题还是 AUTO（系统兜底）就再试一次。
     * 超过这个次数还失败就认了——再往后会话已经聊开了，重命名反而突兀。
     */
    private static final int RETITLE_MAX_SEQUENCE = 3;

    private final IntentRouteService intentRouteService;
    private final SelfManagedReActEngine reActEngine;
    private final PlanAndExecuteEngine planAndExecuteEngine;
    private final TraceEventDispatcher dispatcher;// 推送
    private final ConversationContextService contextService;
    private final AgentConversationAttachmentService attachmentService;
    private final ProfileUpdateCoordinator profileUpdateCoordinator;
    private final ConversationMessageService conversationMessageService;
    private final ConversationTitleService conversationTitleService;

    private final AgentRunSupervisor runSupervisor;

    private final AgentTaskRegistry taskRegistry;

    @Override
    public void handle(AgentChatRequest request, SseEmitter emitter, String taskId,
                       AgentTurnContext turn) {
        // 生成任务由发送接口预先分配 traceId；旧入口在这里现生成
        String traceId = turn != null && turn.getTraceId() != null
                ? turn.getTraceId()
                : UUID.randomUUID().toString();

        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .taskId(taskId)
                .traceId(traceId)
                .conversationId(turn != null ? turn.getConversationId() : request.resolveConversationUuid())
                .userId(request.getUserId())
                .message(request.getMessage())
                .knowledgeBaseIds(request.getKnowledgeBaseIds())
                .fileIds(request.getFileIds())
                .studyContext(request.getStudyContext())
                .build();

        // 生成任务模式：把上下文绑定到生成对象（取消标记、seq 分配都以它为准），
        // 并立刻下发首包，客户端据此确认“这条流是我要续的那次生成”。
        if (taskId != null) {
            taskRegistry.bindContext(taskId, context);
            dispatcher.emit(emitter, context, AgentEventType.TASK_STARTED, Map.of(
                    "taskId", taskId,
                    "status", "RUNNING"
            ));
        }

        // 初始化执行预算（默认 ReAct 预算，路由后按模式调整）
        context.setBudget(ExecutionBudget.reactDefault());

        // ===== 事务 A：先落库（用户消息 + assistant 占位行），再生成 =====
        // 新链路的 openReply 已在发送接口里同步完成（响应要直接带 conversationId/messageId），
        // 只有旧入口（turn == null）才在这里补插消息。
        if (turn == null) {
            try {
                ConversationMessageService.PendingReply state = conversationMessageService.openReply(
                        request.resolveConversationUuid(), request.getMessageUuid(), request.getDraftId(),
                        request.getUserId(), request.getMessage(), request.getFileIds(), traceId, taskId);
                if (state.isDuplicate()) {
                    // 旧兼容入口重复提交同一条 message_uuid：不再生成第二份回答
                    context.setConversationId(state.getConversationId());
                    context.setAssistantMessageId(state.getAssistantMessageId());
                    context.setAssistantSequenceNo(state.getAssistantSequenceNo());
                    dispatcher.error(emitter, context,
                            new IllegalStateException("消息已处理，请刷新会话查看已有回答"));
                    return;
                }
                turn = AgentTurnContext.from(state, traceId);
            } catch (Exception e) {
                // 会话已删除 / 无权访问 / 数据库异常：不写库，直接以错误事件收敛
                log.warn("[AgentOrchestrator] 插入消息失败: traceId={}, cid={}, reason={}",
                        traceId, request.getConversationId(), e.getMessage());
                context.setConversationId(request.getConversationId());
                dispatcher.error(emitter, context,
                        new IllegalStateException(e.getMessage() != null ? e.getMessage() : "会话不可用"));
                return;
            }
        }

        String conversationId = turn.getConversationId();
        request.setConversationId(conversationId);
        request.setConversationUuid(conversationId);
        context.setConversationId(conversationId);
        context.setConversationPk(turn.getConversationPk());
        context.setTurnId(traceId);
        context.setAssistantMessageId(turn.getAssistantMessageId());
        context.setAssistantSequenceNo(turn.getAssistantSequenceNo());
        // 会话 ID 到这一步才确定：绑定后刷新页面才能找回这次生成
        taskRegistry.bindConversation(taskId, conversationId);

        try {
            // 0. 通知前端：会话与占位消息已创建
            if (turn.isCreated()) {
                Map<String, Object> createdPayload = new LinkedHashMap<>();
                createdPayload.put("conversationId", conversationId);
                createdPayload.put("title", turn.getTitle() != null ? turn.getTitle() : "");
                dispatcher.emit(emitter, context, AgentEventType.CONVERSATION_CREATED, createdPayload);
            }

            // 0.1 首条消息：异步把用户消息压缩成会话标题（不阻塞 SSE 首包）。
            //     前端先拿到 conversation.created 里的兜底标题，模型标题稍后通过
            //     agent.conversation.title 事件补上；失败/超时则保留兜底标题。
            //     用序号判断而非 created 标志：前端预传 conversationId 时主表可能已存在，
            //     但消息序号仍为 1，这种情况同样需要生成标题。
            if (turn.getUserSequenceNo() == 1) {
                triggerTitleGeneration(conversationId, request, emitter, context);
            } else if (turn.getUserSequenceNo() <= RETITLE_MAX_SEQUENCE
                    && Conversation.TITLE_SOURCE_AUTO.equals(turn.getTitleSource())) {
                // 前几轮标题还是系统兜底：多半是首条消息没实质内容（"你好"）或上次生成失败。
                // 这时用当前这条消息再试一次，避免整个会话一直挂着一个"你好"。
                triggerTitleGeneration(conversationId, request, emitter, context);
            }

            Map<String, Object> messageCreatedPayload = new LinkedHashMap<>();
            messageCreatedPayload.put("messageId", turn.getAssistantMessageId());
            messageCreatedPayload.put("parentId", turn.getUserMessageId());
            messageCreatedPayload.put("role", "assistant");
            messageCreatedPayload.put("sequenceNo", turn.getAssistantSequenceNo());
            messageCreatedPayload.put("status", "RUNNING");
            dispatcher.emit(emitter, context, AgentEventType.MESSAGE_CREATED, messageCreatedPayload);

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
                    contextService.saveAssistantMessage(conversationId, request.getUserId(), answer,
                            null, context.getTraceId(), null);
                    context.setAssistantMessageSaved(true);
                    dispatcher.complete(emitter, context);
                    return;
                }
            }

            // RAG 检索已改为 Agent 工具注入模式（addRagToolIfNeeded），
            // Agent 在 ReAct 循环中通过 ragRetrieve 工具自主决定何时检索，
            // 不再由编排器自动触发

            // 0.5 显式模式短路：跳过意图路由，直接按用户选择的模式执行
            if (request.getForceMode() != null && !request.getForceMode().isBlank()) {
                handleForcedMode(request, emitter, context, conversationId);
                return; // handleForcedMode 内部通过 supervisor 完成 SSE
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
            addCurrentTimeTool(routeResult);
            addMindMapTool(routeResult);

            // 根据路由结果调整执行预算
            if (routeResult.getRunMode() == AgentRunMode.PLAN_AND_EXECUTE) {
                context.setBudget(ExecutionBudget.planDefault());
            }

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

            // 4. 在监督下执行引擎——保证收敛
            runSupervisor.supervisedRun(context, emitter, context.getBudget(), () -> {
                switch (routeResult.getRunMode()) {
                    case REACT -> {
                        if (routeResult.isNeedsClarification()) {
                            // 澄清短路：直接输出澄清文本，不走 ReAct
                            String clarificationMsg = routeResult.getReason();
                            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                                    "text", clarificationMsg
                            ));
                            contextService.saveAssistantMessage(conversationId, request.getUserId(),
                                    clarificationMsg, null, context.getTraceId(), null);
                            context.setAssistantMessageSaved(true);
                        } else {
                            reActEngine.execute(request, routeResult, context, emitter);
                        }
                    }
                    case PLAN_AND_EXECUTE -> handlePlanAndExecute(request, routeResult, context, emitter);
                    default -> throw new IllegalStateException("不支持的运行模式: " + routeResult.getRunMode());
                }
            });

            // 5. 异步提交画像分析（不阻塞主 SSE 响应）查最近 30 条消息
            //    - getRecentChatMessages(30): 从 DB 查最近 30 条消息，转成 List<ChatMessage>
            //      按 create_time 正序排列，跳过空内容 → LLM 原生多轮对话格式
            //    - submit(): 校验参数后提交后台线程 → extractor.extract(currentUserMessage, history) →
            //      router LLM → extraction LLM → updateStudentProfileTool.execute()
            //      不再使用关键词预过滤——由 router LLM 自行判断是否包含画像信息
            List<ChatMessage> conversationHistory = contextService.getRecentChatMessages(conversationId, 30);
            log.info("[AgentOrchestrator] 提交画像分析: userId={}, conversationId={}, traceId={}, "
                    + "historySize={}, currentMsg={}",
                    request.getUserId(), conversationId, context.getTraceId(),
                    conversationHistory.size(),
                    request.getMessage().substring(0, Math.min(50, request.getMessage().length())));
            profileUpdateCoordinator.submit(request.getUserId(), conversationId, context.getTraceId(),
                    request.getMessage(), conversationHistory);

        } catch (Exception e) {
            // supervisor 外部异常（路由前等）——仍保证收敛
            log.error("[AgentOrchestrator] 执行失败: traceId={}", context.getTraceId(), e);
            runSupervisor.handleUncaughtError(context, emitter, e);
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

    /**
     * 触发异步标题生成。
     * <p>
     * 线程池拒绝（队列满）时会抛 TaskRejectedException——这里是提交点，必须兜住，
     * 否则会把整轮对话打挂。标题只是展示优化，任何异常都降级为"保留首条消息截断的标题"。
     */
    private void triggerTitleGeneration(String conversationId, AgentChatRequest request,
                                        SseEmitter emitter, AgentRuntimeContext context) {
        try {
            conversationTitleService.generateAsync(conversationId, request.getMessage(), emitter, context);
        } catch (Exception e) {
            log.warn("[AgentOrchestrator] 提交标题生成任务失败，保留截断标题: cid={}, reason={}",
                    conversationId, e.getMessage());
        }
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

        // 设置强制调用 ragRetrieve 工具标志
        context.setForceRagRetrieve(true);

        log.info("[AgentOrchestrator] 注入 ragRetrieve 工具，knowledgeBaseIds={}, fileIds={}",
                context.getKnowledgeBaseIds(), context.getFileIds());
    }

    /**
     * 将 getCurrentTime 工具注入到所有路由结果中，确保 Agent 在所有模式下
     * 都能感知当前时间。
     */
    private void addCurrentTimeTool(IntentRouteResult routeResult) {
        List<String> currentTools = routeResult.getRequiredTools() != null
                ? new ArrayList<>(routeResult.getRequiredTools()) : new ArrayList<>();
        if (!currentTools.contains("getCurrentTime")) {
            currentTools.add("getCurrentTime");
            routeResult.setRequiredTools(currentTools);
        }
    }

    /**
     * 将 generateMindMap 工具常驻注入到所有 ReAct 路由结果中，
     * 由 LLM 自主决定何时调用（用户请求整理章节/概念/知识体系结构时）。
     * Plan-and-Execute 模式不注入（其工具由 PlannerAgent 动态决定）。
     */
    private void addMindMapTool(IntentRouteResult routeResult) {
        if (routeResult.getRunMode() != AgentRunMode.REACT) {
            return;
        }
        List<String> currentTools = routeResult.getRequiredTools() != null
                ? new ArrayList<>(routeResult.getRequiredTools()) : new ArrayList<>();
        if (!currentTools.contains("generateMindMap")) {
            currentTools.add("generateMindMap");
            routeResult.setRequiredTools(currentTools);
        }
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
        addCurrentTimeTool(forcedResult);
        addMindMapTool(forcedResult);

        // 根据模式调整预算
        if (forcedResult.getRunMode() == AgentRunMode.PLAN_AND_EXECUTE) {
            context.setBudget(ExecutionBudget.planDefault());
        }

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

        // 在监督下执行引擎
        runSupervisor.supervisedRun(context, emitter, context.getBudget(), () -> {
            switch (forcedResult.getRunMode()) {
                case REACT -> reActEngine.execute(request, forcedResult, context, emitter);
                case PLAN_AND_EXECUTE -> handlePlanAndExecute(request, forcedResult, context, emitter);
                default -> throw new IllegalStateException("不支持的运行模式: " + forcedResult.getRunMode());
            }
        });

        // 异步提交画像分析（不阻塞主 SSE 响应）
        //   - getRecentChatMessages(30): 从 DB 查最近 30 条消息 → List<ChatMessage>
        //   - submit(): 校验参数后提交后台线程 → extractor.extract() →
        //     router LLM → extraction LLM → updateStudentProfileTool.execute()
        //     不再使用关键词预过滤——由 router LLM 自行判断是否包含画像信息
        List<ChatMessage> conversationHistory = contextService.getRecentChatMessages(conversationId, 30);
        log.info("[AgentOrchestrator:forcedMode] 提交画像分析: userId={}, conversationId={}, traceId={}, "
                + "historySize={}, currentMsg={}",
                request.getUserId(), conversationId, context.getTraceId(),
                conversationHistory.size(),
                request.getMessage().substring(0, Math.min(50, request.getMessage().length())));
        profileUpdateCoordinator.submit(request.getUserId(), conversationId, context.getTraceId(),
                request.getMessage(), conversationHistory);
    }

    private IntentRouteResult buildForcedRouteResult(AgentRunMode mode) {
        boolean needsPlanning = mode == AgentRunMode.PLAN_AND_EXECUTE;
        List<String> tools = needsPlanning ? List.of()
                : List.of("getStudentProfile", "queryKnowledge", "queryTasks",
                "listLearningSpaces", "resolveLearningSpace", "getLearningSpaceDashboard", "webSearch");
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

}
