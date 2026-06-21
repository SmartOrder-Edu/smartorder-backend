package com.smartorder.edu.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.clarification.ClarificationService;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.ClarificationPayload;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.dto.PlanContext;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.model.dto.VerificationResult;
import com.smartorder.edu.agent.model.dto.VerificationResult.SuggestedAction;
import com.smartorder.edu.agent.plan.LLMStepVerifier;
import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.plan.PlanSchemaValidator;
import com.smartorder.edu.agent.plan.PlannerAgent;
import com.smartorder.edu.agent.prompt.PromptLoader;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.route.IntentType;
import com.smartorder.edu.agent.service.AgentRunStateService;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolRegistry;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute 混合引擎——替代 PlanExecuteEngine + MultiAgentCoordinator
 * <p>
 * 核心思想：Plan 是全局的（确定做什么、按什么顺序），ReAct 是局部的（每个子任务怎么做）。
 * <p>
 * 三个执行门控制流程：
 * - CLARIFICATION 门：暂停执行，向用户追问，等待回复后继续
 * - CONFIRMATION 门：暂停执行，等待用户确认后才执行有副作用的操作
 * - NORMAL：直接执行 ReAct
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanAndExecuteEngine {

    private final ChatModel chatLanguageModel;
    private final ChatModel planningChatModel; // 规划专用，禁用 thinking 确保纯 JSON 输出
    private final ToolRegistry toolRegistry;
    private final LangChain4jToolAdapter toolAdapter;
    private final PlanSchemaValidator schemaValidator;
    private final PlanDraftService draftService;
    private final ClarificationService clarificationService;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;
    private final SelfManagedReActEngine reActEngine;
    private final LLMStepVerifier stepVerifier;
    private final PromptLoader promptLoader;
    private final AgentRunStateService runStateService;

    /** 单任务最大重试次数 */
    private static final int MAX_RETRIES_PER_TASK = 3;
    /** 单次计划最大重规划次数 */
    private static final int MAX_TOTAL_REPLANS = 5;

    // ============ 公开 API ============

    /**
     * Phase 1: 规划——生成 ExecutionPlan
     */
    public void runPlanning(AgentChatRequest request, IntentRouteResult routeResult,
                            AgentRuntimeContext context, SseEmitter emitter) {
        // 1. 发射阶段变更
        dispatcher.emit(emitter, context, AgentEventType.PHASE_CHANGE, Map.of(
                "phase", "PLANNING",
                "message", "正在分析目标并生成任务计划"
        ));

        // 2. 构建 PlannerAgent prompt（工具需求由 PlannerAgent 在执行计划中动态决定）
        String prompt = buildPlannerPrompt(request, routeResult, context);

        // 预算检查 + 记录 LLM 调用
        if (context.getBudget() != null && context.getBudget().isExceeded()) {
            throw new BudgetExceededException("Plan 规划超出预算: traceId=" + context.getTraceId());
        }
        if (context.getBudget() != null) {
            context.getBudget().recordLlmCall();
        }

        // 3. 调用 PlannerAgent 生成 ExecutionPlan
        String planRaw;
        try {
            PlannerAgent plannerAgent = AiServices.builder(PlannerAgent.class)
                    .chatModel(planningChatModel)
                    .systemMessageProvider(memoryId -> promptLoader.getPlannerCreatePrompt())
                    .build();
            planRaw = plannerAgent.createExecutionPlan(prompt);
            log.info("[PlanAndExecute] PlannerAgent 原始响应 (前500字符): {}",
                    planRaw != null ? planRaw.substring(0, Math.min(500, planRaw.length())) : "null");
        } catch (Exception e) {
            log.error("[PlanAndExecute] PlannerAgent 调用失败", e);
            String errorMsg = "抱歉，生成任务计划时遇到了问题，请稍后再试。";
            dispatcher.emit(emitter, context, AgentEventType.ERROR, Map.of(
                    "message", "规划生成失败: " + e.getMessage()
            ));
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", errorMsg));
            memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                    errorMsg, null, context.getTraceId(), null);
            context.setAssistantMessageSaved(true);
            return;
        }

        // 4. 校验 ExecutionPlan
        ExecutionPlan plan;
        try {
            plan = schemaValidator.validateAndParseExecutionPlan(planRaw);
        } catch (Exception e) {
            log.error("[PlanAndExecute] ExecutionPlan 校验失败: {}, 原始响应(前300字符): {}",
                    e.getMessage(),
                    planRaw != null ? planRaw.substring(0, Math.min(300, planRaw.length())) : "null");
            String errorMsg = "抱歉，生成任务计划时机型返回格式异常，请稍后重试。";
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", errorMsg));
            memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                    errorMsg, null, context.getTraceId(), null);
            context.setAssistantMessageSaved(true);
            return;
        }

        // 5. 初始化 ExecutionPlan 元数据
        plan.setStatus("DRAFT");

        // 6. 检查第一个 task 是否是 CLARIFICATION gate → 立即处理
        ExecutionStep firstStep = plan.getSteps().get(0);
        if ("CLARIFICATION".equals(firstStep.getGate())) {
            handleClarificationGate(plan, 0, context, emitter, request);
            return;
        }

        // 7. 检查是否有 CONFIRMATION gate → 保存草稿并暂停
        boolean hasConfirmationGate = plan.getSteps().stream()
                .anyMatch(t -> "CONFIRMATION".equals(t.getGate()));

        if (hasConfirmationGate) {
            // 先执行 CONFIRMATION gate 之前的 NORMAL 任务
            int firstConfirmationIndex = findFirstConfirmationIndex(plan);
            if (firstConfirmationIndex > 0) {
                // 有前置 NORMAL 任务
                plan.setStatus("EXECUTING");
                String draftId = draftService.saveExecutionPlanDraft(
                        context.getConversationId(), context.getTraceId(),
                        request.getUserId(), plan);
                plan.setConfirmationDraftId(draftId);
                draftService.updateExecutionPlan(draftId, plan);

                // 创建 runState 并转换到 EXECUTING
                runStateService.createRunState(context.getConversationId(), draftId, request.getUserId());
                runStateService.transitionByDraftId(draftId, "EXECUTING", context.getTraceId(), null, null, null);

                // 先发送 plan.created 让前端立即展示 PlanChecklist，
                // 避免前端在 NORMAL 任务执行期间（可能 30-60s）看不到计划结构
                dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                        planCreatedPayload(draftId, plan, true, ""));

                // 再执行 NORMAL 任务（任务状态通过 plan.task.started/completed 实时同步前端）
                executeTasksUntilGate(draftId, plan, firstConfirmationIndex, context, emitter, request);
                return;
            }

            // 第一个任务就是 CONFIRMATION，直接暂停
            String draftId = draftService.saveExecutionPlanDraft(
                    context.getConversationId(), context.getTraceId(),
                    request.getUserId(), plan);

            plan.setStatus("PAUSED_CONFIRMATION");
            plan.setConfirmationDraftId(draftId);
            draftService.updateExecutionPlan(draftId, plan);

            // 创建 runState 并转换到 PAUSED_CONFIRMATION
            runStateService.createRunState(context.getConversationId(), draftId, request.getUserId());
            runStateService.transitionByDraftId(draftId, "PAUSED_CONFIRMATION",
                    context.getTraceId(), "CONFIRMATION", plan.getSteps().get(0).getStepId(), null);

            // 将暂停态信息写入 context，供 done 事件携带
            context.setPlanRunStatus("PAUSED_CONFIRMATION");
            context.setPlanDraftId(draftId);

            // 发射 PLAN_CREATED 事件
            dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                    planCreatedPayload(draftId, plan, true, ""));

            // 输出摘要
            String summaryText = buildPlanSummaryText(plan);
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", summaryText));
            memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                    summaryText, null, context.getTraceId(), null);
            context.setAssistantMessageSaved(true);
            return;
        }

        // 8. 无门 → 保存草稿，发射 PLAN_CREATED，等待用户确认后执行
        String draftId = draftService.saveExecutionPlanDraft(
                context.getConversationId(), context.getTraceId(),
                request.getUserId(), plan);
        plan.setStatus("PENDING_CONFIRM");
        plan.setConfirmationDraftId(draftId);
        draftService.updateExecutionPlan(draftId, plan);

        // 创建 runState 并转换到 WAITING_CONFIRMATION
        runStateService.createRunState(context.getConversationId(), draftId, request.getUserId());
        runStateService.transitionByDraftId(draftId, "WAITING_CONFIRMATION",
                context.getTraceId(), null, null, null);

        // 发射 PLAN_CREATED 事件（前端依赖此事件初始化 currentExecutionPlan）
        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                planCreatedPayload(draftId, plan, false, ""));

        // 输出计划摘要文本
        String summaryText = buildPlanSummaryText(plan);
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", summaryText));
        memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                summaryText, null, context.getTraceId(), null);
        context.setAssistantMessageSaved(true);
    }

    /**
     * Phase 2: 执行 ExecutionPlan（确认后调用）
     * <p>
     * 由 AgentPlanController 调用，创建全新的执行上下文。
     */
    public void executeExecutionPlan(String draftId, Long userId, String conversationId,
                                SseEmitter emitter, String traceId) {
        // 1. 从 DB 加载 ExecutionPlan
        AgentPlanDraft draft = draftService.getByDraftId(draftId);
        if (draft == null) {
            log.error("[PlanAndExecute] 草稿不存在: {}", draftId);
            dispatcher.error(emitter, buildMinimalContext(traceId, conversationId, userId),
                    new IllegalArgumentException("规划草稿不存在"));
            return;
        }

        ExecutionPlan plan = JSON.parseObject(draft.getExecutionPlanJson(), ExecutionPlan.class);
        boolean resumed = resumeConfirmationGateIfNeeded(draftId, plan, null);

        // 2. 构建运行时上下文
        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .budget(ExecutionBudget.planDefault())
                .sequence(draft.getResumeStepIndex() != null
                        ? new java.util.concurrent.atomic.AtomicInteger(draft.getResumeStepIndex() * 100)
                        : new java.util.concurrent.atomic.AtomicInteger(0))
                .build();

        if (resumed) {
            emitResumeIfConfirmationWasCleared(plan, context, emitter);
        }

        executeExecutionPlanInternal(draft, plan, context, emitter, draftId,
                userId, conversationId, resumed);

        // /agent/plan/confirm 不走 AgentRunSupervisor，需显式同步最终状态并关闭 SSE。
        syncFinalPlanRunStatus(draftId, context, "[ExecutePlan]");
        dispatcher.complete(emitter, context);
    }

    /**
     * Phase 2 (with context): 使用父级上下文执行 ExecutionPlan，保持 traceId 和序列号连续性。
     * <p>
     * 由 resumePlan 等内部调用方使用，避免丢失 attachmentContext 等父级状态。
     */
    public void executeExecutionPlanWithContext(String draftId, Long userId,
                                           AgentRuntimeContext parentContext, SseEmitter emitter) {
        AgentPlanDraft draft = draftService.getByDraftId(draftId);
        if (draft == null) {
            log.error("[PlanAndExecute] 草稿不存在: {}", draftId);
            dispatcher.emit(emitter, parentContext,
                    AgentEventType.ERROR, Map.of("message", "规划草稿不存在"));
            return;
        }

        ExecutionPlan plan = JSON.parseObject(draft.getExecutionPlanJson(), ExecutionPlan.class);
        boolean resumed = resumeConfirmationGateIfNeeded(draftId, plan, null);

        // 构建子上下文（继承父级关键字段，保持 traceId 连续性）
        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(parentContext.getTraceId())
                .conversationId(parentContext.getConversationId())
                .userId(parentContext.getUserId())
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .attachmentContext(parentContext.getAttachmentContext())
                .allowedAttachmentFileIds(parentContext.getAllowedAttachmentFileIds())
                .budget(parentContext.getBudget() != null
                        ? parentContext.getBudget()
                        : ExecutionBudget.planDefault())
                .sequence(parentContext.getSequence() != null
                        ? parentContext.getSequence()
                        : new java.util.concurrent.atomic.AtomicInteger(0))
                .build();

        if (resumed) {
            emitResumeIfConfirmationWasCleared(plan, context, emitter);
        }

        executeExecutionPlanInternal(draft, plan, context, emitter, draftId,
                userId, parentContext.getConversationId(), resumed);
    }

    /**
     * 执行 ExecutionPlan 的核心逻辑（共享实现）
     */
    private void executeExecutionPlanInternal(AgentPlanDraft draft, ExecutionPlan plan,
                                         AgentRuntimeContext context, SseEmitter emitter,
                                         String draftId, Long userId, String conversationId,
                                         boolean userConfirmed) {
        // 1. 拓扑排序
        List<ExecutionStep> sortedSteps = topologicalSort(plan.getSteps());

        // 2. 执行任务（带验证+动态重规划）
        Map<String, String> stepOutputs = new LinkedHashMap<>(plan.getStepOutputs());
        if (plan.getUserGoal() != null && !plan.getUserGoal().isBlank()) {
            stepOutputs.putIfAbsent("__userGoal", plan.getUserGoal());
        }
        plan.setStatus("EXECUTING");

        // 更新 runState 到 EXECUTING
        runStateService.transitionByDraftId(draftId, "EXECUTING", context.getTraceId(), null, null, null);

        // 构建 PlanContext（结构化澄清上下文，随执行累积）
        PlanContext planContext = buildPlanContext(plan, context);

        int totalReplans = 0;
        int stepIndex = plan.getCurrentStepIndex();

        while (stepIndex < sortedSteps.size()) {
            // 预算检查：超出则抛出 BudgetExceededException，由 AgentRunSupervisor 捕获
            if (context.getBudget() != null && context.getBudget().isExceeded()) {
                throw new BudgetExceededException("Plan 执行超出预算: traceId=" + context.getTraceId());
            }

            // 客户端断连检查
            if (context.isClientDisconnected()) {
                log.warn("[PlanAndExecute] 客户端已断开，提前退出: traceId={}", context.getTraceId());
                break;
            }

            ExecutionStep task = sortedSteps.get(stepIndex);

            // 跳过已完成的任务
            if ("COMPLETED".equals(task.getStatus())) {
                stepIndex++;
                continue;
            }

            // 检查依赖
            if (!allDependenciesCompleted(task, stepOutputs)) {
                task.setStatus("FAILED");
                task.setErrorMessage("依赖任务未完成: " + task.getDependsOn());
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_FAILED, stepToPayload(task));
                stepIndex++;
                continue;
            }

            // 根据门类型分发（门任务不受验证循环控制）
            if ("CLARIFICATION".equals(task.getGate())) {
                task.setStatus("RUNNING");
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_STARTED, stepToPayload(task));
                handleClarificationGate(plan, stepIndex, context, emitter, null, draftId);
                plan.setCurrentStepIndex(stepIndex);
                draftService.updateExecutionPlan(draftId, plan);
                return;
            }

            if ("CONFIRMATION".equals(task.getGate())) {
                task.setStatus("RUNNING");
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_STARTED, stepToPayload(task));
                plan.setCurrentStepIndex(stepIndex);
                plan.setStatus("PAUSED_CONFIRMATION");
                plan.setConfirmationDraftId(draftId);
                draftService.updateExecutionPlan(draftId, plan);

                // 更新 runState 到 PAUSED_CONFIRMATION
                runStateService.transitionByDraftId(draftId, "PAUSED_CONFIRMATION",
                        context.getTraceId(), "CONFIRMATION", task.getStepId(), null);

                // 将暂停态信息写入 context，供 done 事件携带
                context.setPlanRunStatus("PAUSED_CONFIRMATION");
                context.setPlanDraftId(draftId);

                dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                        planCreatedPayload(draftId, plan, true, ""));

                String confirmText = buildConfirmationText(task, plan);
                dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", confirmText));
                memoryService.saveAssistantMessage(conversationId, userId, confirmText, null,
                        context.getTraceId(), null);
                context.setAssistantMessageSaved(true);
                return;
            }

            // NORMAL 任务：执行 → 验证 → 重试/重规划 循环
            boolean taskCompleted = executeAndVerifyTask(task, stepOutputs, plan, planContext, draftId,
                    context, emitter, conversationId, userId, userConfirmed);

            if (!taskCompleted) {
                // 检查是否因 CLARIFY 而暂停，若是则退出主循环
                if ("PAUSED_CLARIFICATION".equals(plan.getStatus())) {
                    return;
                }
                // 任务验证失败，尝试重规划剩余任务
                if (totalReplans >= MAX_TOTAL_REPLANS) {
                    log.warn("[PlanAndExecute] 达到最大重规划次数 {}, 终止计划", MAX_TOTAL_REPLANS);
                    task.setStatus("FAILED");
                    task.setErrorMessage("超出最大重规划次数限制");
                    dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_FAILED, stepToPayload(task));
                    break;
                }

                totalReplans++;
                boolean replanned = replanRemainingTasks(plan, sortedSteps, stepIndex,
                        task, stepOutputs, context, emitter, draftId);
                if (replanned) {
                    // 重新拓扑排序，从当前索引继续
                    sortedSteps = topologicalSort(plan.getSteps());
                    // stepIndex 保持不变，继续执行重规划后的新任务
                    java.util.Map<String, Object> replanStatus = new java.util.LinkedHashMap<>();
                    replanStatus.put("type", "plan_replanned");
                    replanStatus.put("message", "计划已根据执行反馈动态调整");
                    replanStatus.put("stepIndex", stepIndex);
                    replanStatus.put("remainingTasks", sortedSteps.size() - stepIndex);
                    replanStatus.put("totalReplans", totalReplans);
                    replanStatus.put("plan", plan);
                    replanStatus.put("taskList", toTaskList(draftId, plan));
                    dispatcher.emit(emitter, context, AgentEventType.STATUS, replanStatus);
                } else {
                    // 无法重规划，标记当前任务为 FAILED（而非静默跳过）
                    log.warn("[PlanAndExecute] 重规划失败，标记任务为 FAILED: taskId={}", task.getStepId());
                    task.setStatus("FAILED");
                    task.setErrorMessage("重规划失败，无法继续执行此任务");
                    dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_FAILED, stepToPayload(task));
                    stepIndex++;
                }
            } else {
                stepIndex++;
                if (context.isLearningPlanAwaitingConfirmation()) {
                    plan.setCurrentStepIndex(stepIndex);
                    completeExecutionPlanAfterLearningPlanDraft(plan, draftId, context, emitter);
                    return;
                }
            }

            // 更新 DB
            plan.setCurrentStepIndex(stepIndex);
            draftService.updateExecutionPlan(draftId, plan);
        }

        // 3. 根据任务实际状态设置最终结果
        boolean hasFailed = plan.getSteps().stream()
                .anyMatch(t -> "FAILED".equals(t.getStatus()));
        boolean hasCompleted = plan.getSteps().stream()
                .anyMatch(t -> "COMPLETED".equals(t.getStatus()));
        if (hasFailed && hasCompleted) {
            plan.setStatus("PARTIAL_COMPLETED");
            runStateService.transitionByDraftId(draftId, "COMPLETED");
        } else if (hasFailed && !hasCompleted) {
            plan.setStatus("FAILED");
            runStateService.transitionByDraftId(draftId, "FAILED");
        } else {
            plan.setStatus("COMPLETED");
            runStateService.transitionByDraftId(draftId, "COMPLETED");
        }
        draftService.updateExecutionPlan(draftId, plan);

        // Emit execution summary as STATUS event (for trace panel), not as AI chat text
        emitExecutionCompletedStatus(plan, context, emitter);

        // Generate user-friendly completion message for the AI chat body
        String userFriendlyMsg = buildUserFriendlyCompletionMessage(plan);
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", userFriendlyMsg));
        memoryService.saveAssistantMessage(conversationId, userId,
                userFriendlyMsg, null, context.getTraceId(), null);
        context.setAssistantMessageSaved(true);

        // SSE 关闭由 AgentRunSupervisor 统一负责，此处不再调用 dispatcher.complete()
    }

    private boolean resumeConfirmationGateIfNeeded(String draftId, ExecutionPlan plan, ExecutionStep explicitTask) {
        if (!"PAUSED_CONFIRMATION".equals(plan.getStatus()) && !"PENDING_CONFIRM".equals(plan.getStatus())) {
            return false;
        }

        ExecutionStep task = explicitTask;
        if (task == null && plan.getCurrentStepIndex() >= 0
                && plan.getCurrentStepIndex() < plan.getSteps().size()) {
            task = plan.getSteps().get(plan.getCurrentStepIndex());
        }

        if (task != null && "CONFIRMATION".equals(task.getGate())) {
            task.setGate("NORMAL");
            task.setStatus("PENDING");
        }

        plan.setStatus("EXECUTING");
        draftService.updateExecutionPlan(draftId, plan);
        return true;
    }

    private void emitResumeIfConfirmationWasCleared(ExecutionPlan plan, AgentRuntimeContext context, SseEmitter emitter) {
        int stepIndex = plan.getCurrentStepIndex();
        if (stepIndex < 0 || stepIndex >= plan.getSteps().size()) {
            return;
        }
        ExecutionStep task = plan.getSteps().get(stepIndex);
        dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                "reason", "CONFIRMATION",
                "taskId", task.getStepId(),
                "resumeFromIndex", stepIndex
        ));
    }

    /**
     * 恢复暂停的计划（澄清回复 / 确认词）
     * @deprecated 请使用 {@link #resumeAfterClarification} 或 {@link #resumeAfterConfirmation} 代替
     */
    @Deprecated
    public void resumePlan(AgentChatRequest request, AgentRuntimeContext context, SseEmitter emitter) {
        AgentPlanDraft draft = draftService.getActiveExecutionPlanDraft(context.getConversationId());
        if (draft == null) {
            log.warn("[PlanAndExecute] 未找到活跃的 ExecutionPlan 草稿: convId={}", context.getConversationId());
            return;
        }

        ExecutionPlan plan = JSON.parseObject(draft.getExecutionPlanJson(), ExecutionPlan.class);

        if ("PAUSED_CLARIFICATION".equals(plan.getStatus())) {
            int pausedIndex = plan.getCurrentStepIndex();
            ExecutionStep pausedTask = plan.getSteps().get(pausedIndex);
            Map<String, String> stepOutputs = plan.getStepOutputs();

            // 检查是否为结构化澄清回答
            boolean hasStructuredAnswer = request.getClarificationAnswerJson() != null
                    && !request.getClarificationAnswerJson().isBlank();

            if (hasStructuredAnswer) {
                // 结构化回答路径：解析 ClarificationAnswer，逐 slot 提交
                try {
                    com.smartorder.edu.agent.model.dto.ClarificationAnswer clarAnswer =
                            JSON.parseObject(request.getClarificationAnswerJson(),
                                    com.smartorder.edu.agent.model.dto.ClarificationAnswer.class);

                    ClarificationPayload nextClarification = clarificationService.submitAnswer(
                            clarAnswer.getClarificationId(), clarAnswer.getAnswer());

                    // 写入 stepOutputs 中的结构化 slot
                    com.smartorder.edu.agent.model.entity.AgentClarification clarRecord =
                            clarificationService.getClarificationsForDraft(draft.getDraftId()).stream()
                                    .filter(c -> c.getClarificationId().equals(clarAnswer.getClarificationId()))
                                    .findFirst().orElse(null);
                    if (clarRecord != null) {
                        stepOutputs.put("__clarification." + clarRecord.getSlotKey(), clarAnswer.getAnswer());
                    }

                    // 还有更多 PENDING 澄清？发射下一条，不恢复执行
                    if (nextClarification != null) {
                        plan.setActiveClarificationId(nextClarification.getClarificationId());
                        plan.setClarificationQuestion(nextClarification.getQuestion());
                        draftService.updateExecutionPlan(draft.getDraftId(), plan);

                        // 更新 context，done 事件将携带 PAUSED_CLARIFICATION
                        context.setPlanRunStatus("PAUSED_CLARIFICATION");
                        context.setPlanDraftId(draft.getDraftId());
                        context.setActiveClarificationId(nextClarification.getClarificationId());

                        // 发射下一条 PLAN_PAUSED
                        Map<String, Object> pausePayload = new LinkedHashMap<>();
                        pausePayload.put("reason", "CLARIFICATION");
                        pausePayload.put("taskId", pausedTask.getStepId());
                        pausePayload.put("question", nextClarification.getQuestion());
                        Map<String, Object> clarificationMap = new LinkedHashMap<>();
                        clarificationMap.put("clarificationId", nextClarification.getClarificationId());
                        clarificationMap.put("slotKey", nextClarification.getSlotKey());
                        clarificationMap.put("question", nextClarification.getQuestion());
                        clarificationMap.put("answerType", nextClarification.getAnswerType());
                        clarificationMap.put("options", nextClarification.getOptions() != null
                                ? nextClarification.getOptions() : List.of());
                        clarificationMap.put("slotPriority", nextClarification.getSlotPriority());
                        pausePayload.put("clarification", clarificationMap);
                        dispatcher.emit(emitter, context, AgentEventType.PLAN_PAUSED, pausePayload);

                        // 发射 gate.answered 通知前端回答已被接受
                        dispatcher.emit(emitter, context, AgentEventType.GATE_ANSWERED, Map.of(
                                "clarificationId", clarAnswer.getClarificationId(),
                                "answer", clarAnswer.getAnswer(),
                                "nextClarificationId", nextClarification.getClarificationId(),
                                "status", "MORE_PENDING"
                        ));

                        // 保存确认语
                        String ackMsg = "已收到你的回答，还有一个问题需要确认。";
                        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", ackMsg));
                        memoryService.saveAssistantMessage(context.getConversationId(), context.getUserId(),
                                ackMsg, null, context.getTraceId(), null);

                        // Acknowledge 上一个 clarification
                        clarificationService.acknowledge(clarAnswer.getClarificationId(), ackMsg);
                        return;
                    }

                    // 全部澄清已回答，恢复执行
                    clarificationService.acknowledge(clarAnswer.getClarificationId(), "已收到，继续执行计划");

                    // 发射 gate.answered 通知前端所有澄清已回答
                    dispatcher.emit(emitter, context, AgentEventType.GATE_ANSWERED, Map.of(
                            "clarificationId", clarAnswer.getClarificationId(),
                            "answer", clarAnswer.getAnswer(),
                            "nextClarificationId", null,
                            "status", "ALL_ANSWERED"
                    ));

                } catch (Exception e) {
                    log.warn("[PlanAndExecute] 结构化澄清回答解析失败，回退到自由文本: {}", e.getMessage());
                    // 回退到自由文本路径
                    String userResponse = request.getMessage();
                    stepOutputs.put(pausedTask.getStepId(), "用户澄清: " + userResponse);
                    stepOutputs.put("__userClarification", "用户补充信息: " + userResponse);
                }
            } else {
                // 向后兼容：自由文本回答路径
                String userResponse = request.getMessage();
                stepOutputs.put(pausedTask.getStepId(), "用户澄清: " + userResponse);
                stepOutputs.put("__userClarification", "用户补充信息: " + userResponse);
            }

            stepOutputs.putIfAbsent(pausedTask.getStepId(), buildClarificationStepOutput(pausedTask, stepOutputs));
            plan.setStepOutputs(stepOutputs);
            pausedTask.setStatus("COMPLETED");
            pausedTask.setResultSummary("用户回复: " + truncate(request.getMessage(), 200));
            pausedTask.setGate("NORMAL"); // 清除门

            plan.setCurrentStepIndex(pausedIndex + 1);
            plan.setStatus("EXECUTING");
            plan.setActiveClarificationId(null);

            draftService.updateExecutionPlan(draft.getDraftId(), plan);

            dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                    planCreatedPayload(draft.getDraftId(), plan, false, ""));
            dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_COMPLETED,
                    stepToPayload(pausedTask));

            dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                    "reason", "CLARIFICATION",
                    "taskId", pausedTask.getStepId(),
                    "resumeFromIndex", plan.getCurrentStepIndex()
            ));

            executeExecutionPlanWithContext(draft.getDraftId(), context.getUserId(),
                    context, emitter);

            // 重新读取最终计划状态，确保 done 事件含正确的 runStatus
            syncFinalPlanRunStatus(draft.getDraftId(), context, "[PlanAndExecute]");

        } else if ("PAUSED_CONFIRMATION".equals(plan.getStatus())) {
            // 用户通过聊天确认词确认（前端按钮确认走 /agent/plan/confirm）
            int pausedIndex = plan.getCurrentStepIndex();
            ExecutionStep pausedTask = plan.getSteps().get(pausedIndex);
            resumeConfirmationGateIfNeeded(draft.getDraftId(), plan, pausedTask);

            dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                    "reason", "CONFIRMATION",
                    "taskId", pausedTask.getStepId()
            ));

            executeExecutionPlanWithContext(draft.getDraftId(), context.getUserId(),
                    context, emitter);

            // 重新读取最终计划状态，确保 done 事件含正确的 runStatus
            syncFinalPlanRunStatus(draft.getDraftId(), context, "[PlanAndExecute]");
        }
    }

    // ============ 显式 HITL API ============

    /**
     * 通过专用 API 提交澄清回答并恢复计划执行
     * <p>
     * 不走 /agent/chat/stream，直接绑定 draftId + clarificationId，后端无需推断意图。
     */
    public void resumeAfterClarification(String draftId, String clarificationId, String answer,
                                          String displayAnswer,
                                          Long userId, String conversationId,
                                          SseEmitter emitter, String traceId) {
        AgentPlanDraft draft = draftService.getByDraftId(draftId);
        if (draft == null) {
            log.warn("[ResumeClarification] 草稿不存在: draftId={}", draftId);
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx, new IllegalArgumentException("草稿不存在: " + draftId));
            return;
        }

        // 权限校验：草稿必须属于当前用户
        if (!userId.equals(draft.getUserId())) {
            log.warn("[ResumeClarification] 越权访问: userId={}, draft.userId={}, draftId={}",
                    userId, draft.getUserId(), draftId);
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx,
                    new IllegalArgumentException("无权操作此草稿: draftId=" + draftId));
            return;
        }

        ExecutionPlan plan;
        try {
            plan = JSON.parseObject(draft.getExecutionPlanJson(), ExecutionPlan.class);
        } catch (Exception e) {
            log.error("[ResumeClarification] 反序列化失败: draftId={}", draftId, e);
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx, new IllegalStateException("计划数据解析失败"));
            return;
        }

        if (!"PAUSED_CLARIFICATION".equals(plan.getStatus())) {
            log.warn("[ResumeClarification] 计划非暂停澄清态: status={}", plan.getStatus());
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx, new IllegalStateException("计划当前不在澄清暂停态: " + plan.getStatus()));
            return;
        }

        AgentRuntimeContext context = buildMinimalContext(traceId, conversationId, userId);

        int pausedIndex = plan.getCurrentStepIndex();
        ExecutionStep pausedTask = plan.getSteps().get(pausedIndex);
        Map<String, String> stepOutputs = new LinkedHashMap<>(plan.getStepOutputs());

        // 保存用户回答消息到对话记录（用 displayAnswer 确保刷新后仍可读）
        String savedText = displayAnswer != null && !displayAnswer.isBlank()
                ? "【澄清回答】" + displayAnswer
                : "【澄清回答】" + answer;
        memoryService.saveUserMessage(conversationId, userId, savedText, null);

        // 提交结构化回答（带所有权校验）
        ClarificationPayload nextClarification;
        try {
            nextClarification = clarificationService.submitAnswer(clarificationId, answer, draftId, userId);
        } catch (Exception e) {
            log.error("[ResumeClarification] 提交澄清回答失败: clarificationId={}", clarificationId, e);
            dispatcher.error(emitter, context, e);
            return;
        }

        // 写入 stepOutputs
        com.smartorder.edu.agent.model.entity.AgentClarification clarRecord =
                clarificationService.getClarificationsForDraft(draftId).stream()
                        .filter(c -> c.getClarificationId().equals(clarificationId))
                        .findFirst().orElse(null);
        if (clarRecord != null) {
            stepOutputs.put("__clarification." + clarRecord.getSlotKey(), answer);
        }

        // 发射 agent.gate.answered 事件，通知前端回答已被处理
        Map<String, Object> gateAnsweredPayload = new LinkedHashMap<>();
        gateAnsweredPayload.put("clarificationId", clarificationId);
        gateAnsweredPayload.put("slotKey", clarRecord != null ? clarRecord.getSlotKey() : "");
        gateAnsweredPayload.put("answer", answer);
        gateAnsweredPayload.put("nextClarificationId",
                nextClarification != null ? nextClarification.getClarificationId() : null);
        gateAnsweredPayload.put("status", nextClarification != null ? "MORE_PENDING" : "ALL_ANSWERED");
        dispatcher.emit(emitter, context, AgentEventType.GATE_ANSWERED, gateAnsweredPayload);

        // 还有更多 PENDING 澄清？发射下一条，不恢复执行
        if (nextClarification != null) {
            plan.setActiveClarificationId(nextClarification.getClarificationId());
            plan.setClarificationQuestion(nextClarification.getQuestion());
            plan.setStepOutputs(stepOutputs);
            draftService.updateExecutionPlan(draftId, plan);

            context.setPlanRunStatus("PAUSED_CLARIFICATION");
            context.setPlanDraftId(draftId);
            context.setActiveClarificationId(nextClarification.getClarificationId());

            // 发射 PLAN_PAUSED（下一个问题）
            Map<String, Object> pausePayload = new LinkedHashMap<>();
            pausePayload.put("reason", "CLARIFICATION");
            pausePayload.put("taskId", pausedTask.getStepId());
            pausePayload.put("question", nextClarification.getQuestion());
            Map<String, Object> clarificationMap = new LinkedHashMap<>();
            clarificationMap.put("clarificationId", nextClarification.getClarificationId());
            clarificationMap.put("slotKey", nextClarification.getSlotKey());
            clarificationMap.put("question", nextClarification.getQuestion());
            clarificationMap.put("answerType", nextClarification.getAnswerType());
            clarificationMap.put("options", nextClarification.getOptions() != null
                    ? nextClarification.getOptions() : List.of());
            clarificationMap.put("slotPriority", nextClarification.getSlotPriority());
            pausePayload.put("clarification", clarificationMap);
            dispatcher.emit(emitter, context, AgentEventType.PLAN_PAUSED, pausePayload);

            String ackMsg = "已收到你的回答，还有一个问题需要确认。";
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", ackMsg));
            memoryService.saveAssistantMessage(conversationId, userId, ackMsg, null, traceId, null);
            clarificationService.acknowledge(clarificationId, ackMsg);

            dispatcher.complete(emitter, context);
            return;
        }

        // 全部澄清已回答，恢复执行
        clarificationService.acknowledge(clarificationId, "已收到，继续执行计划");

        stepOutputs.put(pausedTask.getStepId(), buildClarificationStepOutput(pausedTask, stepOutputs));
        plan.setStepOutputs(stepOutputs);
        pausedTask.setStatus("COMPLETED");
        pausedTask.setResultSummary("用户回复: " + truncate(answer, 200));
        pausedTask.setGate("NORMAL");
        plan.setCurrentStepIndex(pausedIndex + 1);
        plan.setStatus("EXECUTING");
        plan.setActiveClarificationId(null);
        draftService.updateExecutionPlan(draftId, plan);

        // 更新 runState 到 EXECUTING（resumeAfterClarification）
        runStateService.transitionByDraftId(draftId, "EXECUTING", traceId, null, null, null);

        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                planCreatedPayload(draftId, plan, false, ""));
        dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_COMPLETED,
                stepToPayload(pausedTask));
        dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                "reason", "CLARIFICATION",
                "taskId", pausedTask.getStepId(),
                "resumeFromIndex", plan.getCurrentStepIndex()
        ));

        executeExecutionPlanWithContext(draftId, userId, context, emitter);

        // 重新加载计划的最终状态，确保前端 done 事件中携带正确的 runStatus
        syncFinalPlanRunStatus(draftId, context, "[ResumeClarification]");

        // 不走 AgentRunSupervisor，需显式关闭 SSE
        dispatcher.complete(emitter, context);
    }

    /**
     * 通过专用 API 确认 CONFIRMATION 门并恢复计划执行
     */
    public void resumeAfterConfirmation(String draftId, Long userId, String conversationId,
                                          SseEmitter emitter, String traceId) {
        AgentPlanDraft draft = draftService.getByDraftId(draftId);
        if (draft == null) {
            log.warn("[ResumeConfirmation] 草稿不存在: draftId={}", draftId);
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx, new IllegalArgumentException("草稿不存在: " + draftId));
            return;
        }

        ExecutionPlan plan;
        try {
            plan = JSON.parseObject(draft.getExecutionPlanJson(), ExecutionPlan.class);
        } catch (Exception e) {
            log.error("[ResumeConfirmation] 反序列化失败: draftId={}", draftId, e);
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx, new IllegalStateException("计划数据解析失败"));
            return;
        }

        if (!"PAUSED_CONFIRMATION".equals(plan.getStatus()) && !"PENDING_CONFIRM".equals(plan.getStatus())) {
            log.warn("[ResumeConfirmation] 计划非可确认态: status={}", plan.getStatus());
            AgentRuntimeContext errCtx = buildMinimalContext(traceId, conversationId, userId);
            dispatcher.error(emitter, errCtx, new IllegalStateException("计划当前不在确认暂停态: " + plan.getStatus()));
            return;
        }

        AgentRuntimeContext context = buildMinimalContext(traceId, conversationId, userId);

        if ("PAUSED_CONFIRMATION".equals(plan.getStatus())) {
            int pausedIndex = plan.getCurrentStepIndex();
            ExecutionStep pausedTask = plan.getSteps().get(pausedIndex);
            resumeConfirmationGateIfNeeded(draftId, plan, pausedTask);
            dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                    "reason", "CONFIRMATION",
                    "taskId", pausedTask.getStepId()
            ));
        }

        // 更新 runState 到 EXECUTING（resumeAfterConfirmation）
        runStateService.transitionByDraftId(draftId, "EXECUTING", traceId, null, null, null);

        executeExecutionPlanWithContext(draftId, userId, context, emitter);

        // 重新加载计划的最终状态，确保前端 done 事件中携带正确的 runStatus
        syncFinalPlanRunStatus(draftId, context, "[ResumeConfirmation]");

        // 不走 AgentRunSupervisor，需显式关闭 SSE
        dispatcher.complete(emitter, context);
    }

    private void syncFinalPlanRunStatus(String draftId, AgentRuntimeContext context, String logPrefix) {
        AgentPlanDraft finalDraft = draftService.getByDraftId(draftId);
        if (finalDraft == null) {
            return;
        }
        try {
            ExecutionPlan finalPlan = JSON.parseObject(
                    finalDraft.getExecutionPlanJson(), ExecutionPlan.class);
            context.setPlanRunStatus(finalPlan.getStatus());
            context.setPlanDraftId(draftId);
            context.setActiveClarificationId(finalPlan.getActiveClarificationId());
        } catch (Exception e) {
            log.warn("{} 读取最终计划状态失败: {}", logPrefix, e.getMessage());
        }
    }

    private void completeExecutionPlanAfterLearningPlanDraft(ExecutionPlan plan, String draftId,
                                                             AgentRuntimeContext context,
                                                             SseEmitter emitter) {
        plan.setStatus("COMPLETED");
        draftService.updateExecutionPlan(draftId, plan);
        runStateService.transitionByDraftId(draftId, "COMPLETED");
        context.setPlanRunStatus("COMPLETED");
        context.setPlanDraftId(draftId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "learning_plan_waiting_confirmation");
        payload.put("executionDraftId", draftId);
        payload.put("learningPlanDraftId", context.getLearningPlanDraftId());
        payload.put("message", "学习计划草稿已生成，等待用户审阅确认。");
        dispatcher.emit(emitter, context, AgentEventType.STATUS, payload);

        log.info("[PlanAndExecute] ExecutionPlan 已在学习计划草稿产出后结束: draftId={}, learningDraftId={}",
                draftId, context.getLearningPlanDraftId());
    }

    private AgentRuntimeContext buildMinimalContext(String traceId, String conversationId, Long userId) {
        return AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .budget(ExecutionBudget.planDefault())
                .sequence(new AtomicInteger(0))
                .build();
    }

    // ============ 私有方法 ============

    private String buildPlannerPrompt(AgentChatRequest request, IntentRouteResult routeResult,
                                       AgentRuntimeContext context) {
        String attachmentSection = "";
        if (context.getAttachmentContext() != null && !context.getAttachmentContext().isBlank()) {
            attachmentSection = "\n用户上传附件摘录：\n" + context.getAttachmentContext();
        }

        // 会话摘要（如有）
        String conversationSummary = "";
        try {
            String summary = memoryService.getSummaryText(context.getConversationId());
            if (summary != null) {
                conversationSummary = "\n历史对话摘要：\n" + summary;
            }
        } catch (Exception e) {
            log.debug("[PlannerPrompt] 获取会话摘要失败（非关键）: {}", e.getMessage());
        }

        // 用户画像（如有——由 ProfileTool 在意图路由阶段获取）
        String userProfileSection = "";
        // 预留：可通过 context 扩展字段传递画像快照

        String planHint = routeResult.getPlanHint() != null ? routeResult.getPlanHint() : "general";

        return PromptLoader.format(promptLoader.getPlannerInputTemplate(),
                request.getMessage(), planHint, attachmentSection,
                conversationSummary, userProfileSection);
    }

    /**
     * @deprecated 请使用 {@link #buildPlanContext} + {@link #buildTaskSystemMessage(ExecutionStep, PlanContext)} 代替。
     * PlanContext 将澄清上下文作为独立段落注入 prompt，不修改 task.description 原文。
     */
    @Deprecated
    private void applyClarificationToRemainingSteps(ExecutionPlan plan, int pausedIndex,
                                                     Map<String, String> stepOutputs) {
        // 此方法已废弃。澄清上下文现已通过 buildPlanContext() + buildTaskSystemMessage()
        // 作为独立段落注入 system prompt，不再修改 task.description 原文。
        // 保留空实现以确保编译兼容，外部调用已被移除。
        log.debug("[PlanAndExecute] applyClarificationToRemainingSteps is deprecated and no-op. "
                + "Clarification context is injected via PlanContext in buildTaskSystemMessage.");
    }

    private void handleClarificationGate(ExecutionPlan plan, int stepIndex,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          AgentChatRequest request) {
        handleClarificationGate(plan, stepIndex, context, emitter, request, null);
    }

    private void handleClarificationGate(ExecutionPlan plan, int stepIndex,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          AgentChatRequest request, String existingDraftId) {
        ExecutionStep task = plan.getSteps().get(stepIndex);
        String question = task.getDescription();

        task.setStatus("PAUSED");
        plan.setStatus("PAUSED_CLARIFICATION");
        plan.setClarificationQuestion(question);
        plan.setCurrentStepIndex(stepIndex);

        String draftId = existingDraftId;
        boolean isNewDraft = (draftId == null || draftId.isBlank());
        if (isNewDraft) {
            draftId = draftService.saveExecutionPlanDraft(
                    context.getConversationId(), context.getTraceId(),
                    request != null ? request.getUserId() : context.getUserId(), plan);
        }
        plan.setConfirmationDraftId(draftId);
        draftService.updateExecutionPlan(draftId, plan);

        // 创建/更新 runState
        Long uid = request != null ? request.getUserId() : context.getUserId();
        if (isNewDraft) {
            runStateService.createRunState(context.getConversationId(), draftId, uid);
        }
        runStateService.transitionByDraftId(draftId, "PAUSED_CLARIFICATION",
                context.getTraceId(), "CLARIFICATION", task.getStepId(), null);

        // 创建结构化澄清记录（从 ExecutionStep 的 clarificationSlots 提取）
        ClarificationPayload firstClarification = clarificationService.createClarifications(
                task, draftId, context.getConversationId(), context.getTraceId(),
                request != null ? request.getUserId() : context.getUserId());

        // 设置当前活跃澄清 ID
        if (firstClarification != null) {
            plan.setActiveClarificationId(firstClarification.getClarificationId());
            question = firstClarification.getQuestion(); // 使用结构化问题文本
            plan.setClarificationQuestion(question);
            draftService.updateExecutionPlan(draftId, plan);
        }

        // 将暂停态信息写入 context，供 done 事件携带
        context.setPlanRunStatus("PAUSED_CLARIFICATION");
        context.setPlanDraftId(draftId);
        context.setActiveClarificationId(firstClarification != null ? firstClarification.getClarificationId() : null);

        // 先发射 PLAN_CREATED，确保前端初始化 currentExecutionPlan，
        // 再发射 PLAN_PAUSED 更新状态为暂停
        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                planCreatedPayload(draftId, plan, false, question));

        // 发射暂停事件（增强版：包含结构化 clarification 信息）
        Map<String, Object> pausePayload = new LinkedHashMap<>();
        pausePayload.put("reason", "CLARIFICATION");
        pausePayload.put("taskId", task.getStepId());
        pausePayload.put("question", question); // 向后兼容
        if (firstClarification != null) {
            Map<String, Object> clarificationMap = new LinkedHashMap<>();
            clarificationMap.put("clarificationId", firstClarification.getClarificationId());
            clarificationMap.put("slotKey", firstClarification.getSlotKey());
            clarificationMap.put("question", firstClarification.getQuestion());
            clarificationMap.put("answerType", firstClarification.getAnswerType());
            clarificationMap.put("options", firstClarification.getOptions() != null
                    ? firstClarification.getOptions() : List.of());
            clarificationMap.put("slotPriority", firstClarification.getSlotPriority());
            pausePayload.put("clarification", clarificationMap);
        }
        dispatcher.emit(emitter, context, AgentEventType.PLAN_PAUSED, pausePayload);

        // 保存澄清消息，metadataJson 存储结构化信息用于历史回放
        String metadataJson = firstClarification != null ? JSON.toJSONString(firstClarification) : null;
        memoryService.saveAssistantMessage(context.getConversationId(),
                request != null ? request.getUserId() : context.getUserId(),
                question, null, context.getTraceId(), metadataJson);
        context.setAssistantMessageSaved(true);
    }

    /**
     * 执行任务直到遇到确认门（执行 CONFIRMATION 之前的 NORMAL 任务），带验证
     */
    private void executeTasksUntilGate(String draftId, ExecutionPlan plan, int gateIndex,
                                        AgentRuntimeContext context, SseEmitter emitter,
                                        AgentChatRequest request) {
        Map<String, String> stepOutputs = new LinkedHashMap<>(plan.getStepOutputs());
        if (plan.getUserGoal() != null && !plan.getUserGoal().isBlank()) {
            stepOutputs.putIfAbsent("__userGoal", plan.getUserGoal());
        }
        String conversationId = context.getConversationId();
        Long userId = request != null ? request.getUserId() : context.getUserId();

        for (int i = 0; i < gateIndex; i++) {
            ExecutionStep task = plan.getSteps().get(i);

            if ("COMPLETED".equals(task.getStatus())) continue;
            if (!"NORMAL".equals(task.getGate())) continue;

            PlanContext planContext = buildPlanContext(plan, context);
            executeAndVerifyTask(task, stepOutputs, plan, planContext, draftId, context, emitter,
                    conversationId, userId, false);

            // 检查是否因 CLARIFY 而暂停，若是则退出
            if ("PAUSED_CLARIFICATION".equals(plan.getStatus())) {
                return;
            }

            plan.setCurrentStepIndex(i + 1);
            draftService.updateExecutionPlan(draftId, plan);
        }

        // 执行到确认门前，暂停
        plan.setStatus("PAUSED_CONFIRMATION");
        plan.setConfirmationDraftId(draftId);
        draftService.updateExecutionPlan(draftId, plan);

        // 更新 runState 到 PAUSED_CONFIRMATION
        ExecutionStep gateTask = plan.getSteps().get(gateIndex);
        runStateService.transitionByDraftId(draftId, "PAUSED_CONFIRMATION",
                context.getTraceId(), "CONFIRMATION", gateTask.getStepId(), null);

        // 将暂停态信息写入 context，供 done 事件携带
        context.setPlanRunStatus("PAUSED_CONFIRMATION");
        context.setPlanDraftId(draftId);

        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                planCreatedPayload(draftId, plan, true, ""));

        String summaryText = buildPlanSummaryText(plan);
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", summaryText));
        memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                summaryText, null, context.getTraceId(), null);
        context.setAssistantMessageSaved(true);
    }

    /**
     * 通过 ReAct 引擎执行单个任务
     */
    private String executeTaskViaReAct(ExecutionStep task, Map<String, String> previousOutputs,
                                        AgentRuntimeContext context, SseEmitter emitter,
                                        boolean confirmationGranted) {
        return executeTaskViaReAct(task, previousOutputs, null, context, emitter, confirmationGranted);
    }

    private String executeTaskViaReAct(ExecutionStep task, Map<String, String> previousOutputs,
                                        PlanContext planContext,
                                        AgentRuntimeContext context, SseEmitter emitter,
                                        boolean confirmationGranted) {
        // 1. 构建任务专属系统消息（支持 PlanContext 结构化注入）
        String taskSystemMessage = buildTaskSystemMessage(task, previousOutputs, planContext);

        // 2. 构建受限的 IntentRouteResult（只包含本任务需要的工具）
        IntentRouteResult taskRoute = IntentRouteResult.builder()
                .intentType("task_execution")
                .intentEnum(IntentType.TASK_MANAGEMENT)
                .runMode(AgentRunMode.REACT)
                .requiredTools(mergeWithAskUser(task.getRequiredTools()))
                .confidence(1.0)
                .build();

        // 3. 构建 AgentChatRequest
        AgentChatRequest taskRequest = new AgentChatRequest();
        taskRequest.setUserId(context.getUserId());
        taskRequest.setConversationId(context.getConversationId());
        taskRequest.setMessage(task.getDescription());

        // 4. 通过 ReAct 引擎静默执行子任务，结果只进入步骤状态，不进入 AI 正文
        return reActEngine.executeInternalTask(taskRequest, taskRoute, context, emitter,
                taskSystemMessage, previousOutputs, confirmationGranted);
    }

    private String buildTaskSystemMessage(ExecutionStep task, Map<String, String> previousOutputs) {
        return buildTaskSystemMessage(task, previousOutputs, null);
    }

    /**
     * 构建任务系统消息——支持 PlanContext 结构化注入
     */
    private String buildTaskSystemMessage(ExecutionStep task, Map<String, String> previousOutputs,
                                           PlanContext planContext) {
        StringBuilder prevSb = new StringBuilder();

        // 1. PlanContext 澄清上下文（独立段落，不修改 task.description）
        if (planContext != null && !planContext.getClarificationAnswers().isEmpty()) {
            prevSb.append("\n用户补充信息：\n");
            for (Map.Entry<String, String> entry : planContext.getClarificationAnswers().entrySet()) {
                prevSb.append("- ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append("\n");
            }
        }

        // 2. 前置步骤产出
        Map<String, String> stepResults = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : previousOutputs.entrySet()) {
            if (!entry.getKey().startsWith("__")) {
                stepResults.put(entry.getKey(), entry.getValue());
            }
        }
        if (!stepResults.isEmpty()) {
            prevSb.append("\n前置任务结果：\n");
            for (Map.Entry<String, String> entry : stepResults.entrySet()) {
                prevSb.append("- ").append(entry.getKey()).append(": ")
                        .append(truncate(entry.getValue(), 300)).append("\n");
            }
        }

        return PromptLoader.format(promptLoader.getTaskSystemMsgTemplate(),
                task.getStepId(),
                task.getDescription(),
                task.getExpectedOutput() != null ? task.getExpectedOutput() : "根据任务完成",
                prevSb.toString());
    }

    /**
     * 从 stepOutputs 中构建 PlanContext（结构化澄清上下文）
     */
    private PlanContext buildPlanContext(ExecutionPlan plan, AgentRuntimeContext context) {
        Map<String, String> clarificationAnswers = new LinkedHashMap<>();
        Map<String, String> stepOutputs = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : plan.getStepOutputs().entrySet()) {
            if (entry.getKey().startsWith("__clarification.")) {
                String slotKey = entry.getKey().substring("__clarification.".length());
                clarificationAnswers.put(slotKey, entry.getValue());
            } else if (!entry.getKey().startsWith("__")) {
                stepOutputs.put(entry.getKey(), entry.getValue());
            }
        }

        return PlanContext.builder()
                .userGoal(plan.getUserGoal())
                .attachmentSummary(context.getAttachmentContext())
                .clarificationAnswers(clarificationAnswers)
                .stepOutputs(stepOutputs)
                .build();
    }

    // ============ 验证与重规划 ============

    /**
     * 执行单个任务并进行质量验证，验证失败时自动重试（最多 MAX_RETRIES_PER_TASK 次）
     *
     * @return true 表示任务最终通过验证，false 表示需要外部重规划
     */
    private boolean executeAndVerifyTask(ExecutionStep task, Map<String, String> stepOutputs,
                                          ExecutionPlan plan, PlanContext planContext, String draftId,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          String conversationId, Long userId,
                                          boolean userConfirmed) {
        // 仅当用户已通过确认门确认且任务风险等级要求确认时，才授予写权限
        boolean confirmationGranted = userConfirmed && "WRITE_CONFIRMED".equals(task.getRiskLevel());

        for (int attempt = 0; attempt <= MAX_RETRIES_PER_TASK; attempt++) {
            task.setStatus("RUNNING");
            dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_STARTED, stepToPayload(task));

            String result;
            try {
                if (attempt > 0) {
                    // 重试时使用修正后的描述
                    log.info("[PlanAndExecute] 重试任务: taskId={}, attempt={}/{}, revisedDesc={}",
                            task.getStepId(), attempt, MAX_RETRIES_PER_TASK, task.getDescription());
                    dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                            "type", "task_retry",
                            "taskId", task.getStepId(),
                            "attempt", attempt,
                            "maxRetries", MAX_RETRIES_PER_TASK
                    ));
                }

                result = executeTaskViaReAct(task, stepOutputs, planContext, context, emitter, confirmationGranted);
            } catch (Exception e) {
                log.error("[PlanAndExecute] 任务执行异常: taskId={}", task.getStepId(), e);
                task.setErrorMessage(e.getMessage());
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_FAILED, stepToPayload(task));
                // 执行异常直接算失败，不重试
                return false;
            }

            // createLearningPlan 工具已经保存 LEARNING_PLAN 草稿并发出预览事件。
            // 这是目标规划链路的正常暂停点，不再交给 LLM verifier 复核，避免误判后重复生成草稿。
            if (context.isLearningPlanAwaitingConfirmation()) {
                String learningPlanDraftId = context.getLearningPlanDraftId();
                if (context.getClarificationPayload() != null) {
                    log.info("[PlanAndExecute] 学习计划草稿已生成，忽略后续 askUser 确认: taskId={}, slotKey={}, learningDraftId={}",
                            task.getStepId(), context.getClarificationPayload().get("slotKey"), learningPlanDraftId);
                    context.setClarificationPayload(null);
                }
                String summary = learningPlanDraftId != null && !learningPlanDraftId.isBlank()
                        ? "学习计划草稿已生成，等待用户审阅确认。draftId=" + learningPlanDraftId
                        : "学习计划草稿已生成，等待用户审阅确认。";
                task.setStatus("COMPLETED");
                task.setResultSummary(summary);
                stepOutputs.put(task.getStepId(), summary);
                if (learningPlanDraftId != null && !learningPlanDraftId.isBlank()) {
                    stepOutputs.put("__learningPlanDraftId", learningPlanDraftId);
                }
                plan.setStepOutputs(stepOutputs);
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_COMPLETED, stepToPayload(task));
                log.info("[PlanAndExecute] 学习计划草稿已产出，跳过步骤验证: taskId={}, learningDraftId={}",
                        task.getStepId(), learningPlanDraftId);
                return true;
            }

            // askUser 工具被调用：LLM 需要向用户澄清信息。
            // 注意：必须放在 learningPlanAwaitingConfirmation 之后，避免 createLearningPlan
            // 已生成确认卡片后，模型又用 askUser 追加一次“是否确认创建”的重复确认。
            if (context.getClarificationPayload() != null) {
                log.info("[PlanAndExecute] askUser 工具触发澄清门: taskId={}, slotKey={}",
                        task.getStepId(), context.getClarificationPayload().get("slotKey"));
                handleAskUserClarification(task, plan, context, emitter, draftId);
                return false;
            }

            // 验证结果质量
            VerificationResult verification = stepVerifier.verify(task, result, stepOutputs);

            if (verification.isPassed()) {
                task.setStatus("COMPLETED");
                task.setResultSummary(truncate(result, 500));
                stepOutputs.put(task.getStepId(), truncate(result, 2000));
                plan.setStepOutputs(stepOutputs);
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_COMPLETED, stepToPayload(task));
                log.info("[PlanAndExecute] 任务验证通过: taskId={}", task.getStepId());
                return true;
            }

            // 验证失败，根据建议动作处理
            log.warn("[PlanAndExecute] 任务验证失败: taskId={}, action={}, feedback={}",
                    task.getStepId(), verification.getAction(),
                    truncate(verification.getFeedback(), 100));

            switch (verification.getAction()) {
                case RETRY -> {
                    if (attempt < MAX_RETRIES_PER_TASK) {
                        // 用修正描述和工具重试
                        if (verification.getRevisedDescription() != null) {
                            task.setDescription(verification.getRevisedDescription());
                        }
                        if (verification.getRevisedTools() != null && !verification.getRevisedTools().isEmpty()) {
                            task.setRequiredTools(verification.getRevisedTools());
                        }
                        task.setRetryCount(task.getRetryCount() + 1);
                        // 继续 for 循环，重试
                    } else {
                        log.warn("[PlanAndExecute] 任务重试次数耗尽: taskId={}", task.getStepId());
                        return false; // 需要重规划
                    }
                }
                case REPLAN -> {
                    return false; // 需要重规划
                }
                case SKIP -> {
                    task.setStatus("SKIPPED");
                    task.setErrorMessage("验证未通过，已跳过: " + verification.getFeedback());
                    dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_FAILED, stepToPayload(task));
                    return true; // 跳过视为完成（不阻塞后续任务）
                }
                case CLARIFY -> {
                    // 转为澄清门，暂停执行
                    task.setGate("CLARIFICATION");
                    task.setDescription(verification.getFeedback());
                    // 为 verification CLARIFY 创建单条 TEXT 类型 slot
                    task.setClarificationSlots(List.of(
                            ExecutionStep.ClarificationSlot.builder()
                                    .slotKey("verification_clarify")
                                    .question(verification.getFeedback())
                                    .answerType("TEXT")
                                    .slotPriority(1)
                                    .build()
                    ));
                    task.setClarificationSlotsJson(JSON.toJSONString(task.getClarificationSlots()));
                    task.setStatus("PAUSED");
                    handleClarificationGate(plan,
                            plan.getSteps().indexOf(task), context, emitter, null, draftId);
                    return false; // 暂停
                }
                default -> { // CONTINUE (should not happen when !passed)
                    task.setStatus("COMPLETED");
                    task.setResultSummary(truncate(result, 500));
                    stepOutputs.put(task.getStepId(), truncate(result, 2000));
                    plan.setStepOutputs(stepOutputs);
                    dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_COMPLETED, stepToPayload(task));
                    return true;
                }
            }
        }

        return false; // 重试耗尽
    }

    /**
     * 处理 askUser 工具触发的澄清门：将 AgentRuntimeContext.clarificationPayload
     * 转换为 ExecutionStep.ClarificationSlot，直接修改传入的 plan 对象并暂停执行。
     */
    private void handleAskUserClarification(ExecutionStep task, ExecutionPlan plan,
                                            AgentRuntimeContext context,
                                            SseEmitter emitter, String draftId) {
        Map<String, Object> cp = context.getClarificationPayload();

        // 解析 options
        List<ClarificationPayload.SelectOption> selectOptions = parseAskUserOptions(
                cp.get("options"));

        task.setClarificationSlots(List.of(
                ExecutionStep.ClarificationSlot.builder()
                        .slotKey((String) cp.getOrDefault("slotKey", "general"))
                        .question((String) cp.getOrDefault("question", "请提供更多信息"))
                        .answerType((String) cp.getOrDefault("answerType", "TEXT"))
                        .options(selectOptions)
                        .slotPriority(1)
                        .build()
        ));
        task.setClarificationSlotsJson(JSON.toJSONString(task.getClarificationSlots()));
        task.setGate("CLARIFICATION");
        task.setDescription((String) cp.getOrDefault("question", "请提供更多信息"));
        task.setStatus("PAUSED");

        // 清除 payload 防止重复处理
        context.setClarificationPayload(null);

        // 直接使用当前 plan 对象调用 handleClarificationGate，确保状态同步
        int stepIndex = plan.getSteps().indexOf(task);
        handleClarificationGate(plan, stepIndex, context, emitter, null, draftId);
    }

    /**
     * 解析 askUser 工具传入的 options 参数。
     * LLM 可能传入 List&lt;Map&gt; 或 List&lt;SelectOption&gt; 格式。
     */
    @SuppressWarnings("unchecked")
    private List<ClarificationPayload.SelectOption> parseAskUserOptions(Object options) {
        if (options == null) return List.of();
        if (options instanceof List<?> list && !list.isEmpty()) {
            try {
                // 尝试直接解析为 SelectOption 列表
                return JSON.parseArray(JSON.toJSONString(list), ClarificationPayload.SelectOption.class);
            } catch (Exception e) {
                log.debug("[PlanAndExecute] options 解析失败，返回空列表: {}", e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * 调用 PlannerAgent.replan() 动态重规划剩余任务
     *
     * @return true 表示重规划成功，sortedSteps 已被更新
     */
    private boolean replanRemainingTasks(ExecutionPlan plan, List<ExecutionStep> sortedSteps,
                                          int failedTaskIndex, ExecutionStep failedTask,
                                          Map<String, String> stepOutputs,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          String draftId) {
        try {
            // 1. 构建重规划 prompt
            String replanPrompt = buildReplanPrompt(plan, failedTask, stepOutputs);

            // 2. 调用 PlannerAgent.replan()
            if (context.getBudget() != null) {
                context.getBudget().recordLlmCall();
            }
            PlannerAgent plannerAgent = AiServices.builder(PlannerAgent.class)
                    .chatModel(planningChatModel)
                    .systemMessageProvider(memoryId -> promptLoader.getPlannerReplanPrompt())
                    .build();
            String replanResponse = plannerAgent.replan(replanPrompt);

            // 3. 解析重规划结果
            String cleanJson = extractJson(replanResponse);
            List<ExecutionStep> newRemainingTasks;
            try {
                newRemainingTasks = com.alibaba.fastjson.JSON.parseArray(cleanJson, ExecutionStep.class);
            } catch (Exception e) {
                log.error("[PlanAndExecute] 重规划 JSON 解析失败: {}", e.getMessage());
                return false;
            }

            if (newRemainingTasks == null || newRemainingTasks.isEmpty()) {
                log.warn("[PlanAndExecute] 重规划返回空任务列表");
                return false;
            }

            // 4. 保留已完成的任务，替换剩余任务
            List<ExecutionStep> completedTasks = new ArrayList<>();
            for (int i = 0; i < failedTaskIndex; i++) {
                completedTasks.add(sortedSteps.get(i));
            }

            List<ExecutionStep> mergedTasks = new ArrayList<>(completedTasks);
            mergedTasks.addAll(newRemainingTasks);

            // 5. 校验新的任务列表
            ExecutionPlan newExecutionPlan = new ExecutionPlan();
            newExecutionPlan.setSteps(mergedTasks);
            // 做一次拓扑排序校验（检测循环依赖）
            try {
                topologicalSort(newExecutionPlan.getSteps());
            } catch (IllegalStateException e) {
                log.error("[PlanAndExecute] 重规划产生循环依赖: {}", e.getMessage());
                return false;
            }

            // 6. 替换 plan 并持久化
            plan.setSteps(mergedTasks);
            draftService.updateExecutionPlan(draftId, plan);

            log.info("[PlanAndExecute] 重规划成功: failedTaskId={}, newRemainingCount={}, totalTasks={}",
                    failedTask.getStepId(), newRemainingTasks.size(), mergedTasks.size());
            return true;

        } catch (Exception e) {
            log.error("[PlanAndExecute] 重规划失败: taskId={}", failedTask.getStepId(), e);
            return false;
        }
    }

    private String buildReplanPrompt(ExecutionPlan plan, ExecutionStep failedTask,
                                     Map<String, String> stepOutputs) {
        StringBuilder completedSb = new StringBuilder();
        for (ExecutionStep task : plan.getSteps()) {
            if ("COMPLETED".equals(task.getStatus())) {
                completedSb.append("- ").append(task.getStepId()).append(": ").append(task.getDescription()).append("\n");
                completedSb.append("  结果: ").append(truncate(task.getResultSummary(), 200)).append("\n");
            }
        }

        StringBuilder failedSb = new StringBuilder();
        failedSb.append("- ").append(failedTask.getStepId()).append(": ").append(failedTask.getDescription()).append("\n");
        failedSb.append("  失败原因: ").append(failedTask.getErrorMessage() != null
                ? failedTask.getErrorMessage() : "结果未通过质量验证").append("\n");
        failedSb.append("  预期产出: ").append(failedTask.getExpectedOutput() != null
                ? failedTask.getExpectedOutput() : "根据任务描述完成").append("\n");

        StringBuilder remainingSb = new StringBuilder();
        for (ExecutionStep task : plan.getSteps()) {
            if (!"COMPLETED".equals(task.getStatus()) && !task.getStepId().equals(failedTask.getStepId())) {
                remainingSb.append("- ").append(task.getStepId()).append(": ").append(task.getDescription()).append("\n");
            }
        }

        return PromptLoader.format(promptLoader.getReplanInputTemplate(),
                plan.getUserGoal(),
                completedSb.toString(),
                failedSb.toString(),
                remainingSb.toString());
    }

    private String extractJson(String response) {
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            jsonStart = response.indexOf('\n', jsonStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        int braceStart = response.indexOf('{');
        if (braceStart >= 0) {
            int braceEnd = response.lastIndexOf('}');
            if (braceEnd > braceStart) {
                return response.substring(braceStart, braceEnd + 1).trim();
            }
        }
        // 可能是数组格式（replan 返回）
        int bracketStart = response.indexOf('[');
        if (bracketStart >= 0) {
            int bracketEnd = response.lastIndexOf(']');
            if (bracketEnd > bracketStart) {
                return response.substring(bracketStart, bracketEnd + 1).trim();
            }
        }
        return response;
    }

    // ============ 拓扑排序 ============

    private List<ExecutionStep> topologicalSort(List<ExecutionStep> tasks) {
        Map<String, ExecutionStep> taskMap = tasks.stream()
                .collect(Collectors.toMap(ExecutionStep::getStepId, t -> t));

        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (ExecutionStep task : tasks) {
            adjacency.put(task.getStepId(), new ArrayList<>());
            inDegree.putIfAbsent(task.getStepId(), 0);
        }

        for (ExecutionStep task : tasks) {
            for (String dep : task.getDependsOn()) {
                adjacency.get(dep).add(task.getStepId());
                inDegree.merge(task.getStepId(), 1, Integer::sum);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<ExecutionStep> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String taskId = queue.poll();
            result.add(taskMap.get(taskId));
            for (String neighbor : adjacency.getOrDefault(taskId, List.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (result.size() != tasks.size()) {
            throw new IllegalStateException("ExecutionPlan 包含循环依赖");
        }

        return result;
    }

    // ============ 辅助方法 ============

    private boolean allDependenciesCompleted(ExecutionStep task, Map<String, String> stepOutputs) {
        for (String dep : task.getDependsOn()) {
            if (!stepOutputs.containsKey(dep)) return false;
        }
        return true;
    }

    private String buildClarificationStepOutput(ExecutionStep task, Map<String, String> stepOutputs) {
        StringBuilder sb = new StringBuilder("用户已补充澄清信息");
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("（").append(task.getDescription()).append("）");
        }

        List<String> answers = stepOutputs.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("__clarification."))
                .map(entry -> entry.getKey().substring("__clarification.".length()) + ": " + entry.getValue())
                .toList();

        if (!answers.isEmpty()) {
            sb.append("：").append(String.join("；", answers));
        } else {
            sb.append("。");
        }
        return sb.toString();
    }

    private int findFirstConfirmationIndex(ExecutionPlan plan) {
        for (int i = 0; i < plan.getSteps().size(); i++) {
            if ("CONFIRMATION".equals(plan.getSteps().get(i).getGate())) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> stepToPayload(ExecutionStep task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", task.getStepId());
        payload.put("taskId", task.getStepId());
        payload.put("description", task.getDescription());
        payload.put("status", task.getStatus());
        payload.put("gate", task.getGate());
        payload.put("riskLevel", task.getRiskLevel());
        payload.put("requiredTools", task.getRequiredTools());
        if (task.getResultSummary() != null) payload.put("resultSummary", task.getResultSummary());
        if (task.getErrorMessage() != null) payload.put("errorMessage", task.getErrorMessage());
        return payload;
    }

    private Map<String, Object> planCreatedPayload(String draftId, ExecutionPlan plan,
                                                   boolean needsConfirmation,
                                                   String clarificationQuestion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", draftId);
        payload.put("plan", plan);
        payload.put("taskList", toTaskList(draftId, plan));
        payload.put("needsConfirmation", needsConfirmation);
        payload.put("clarificationQuestion", clarificationQuestion != null ? clarificationQuestion : "");
        return payload;
    }

    private Map<String, Object> toTaskList(String draftId, ExecutionPlan plan) {
        Map<String, Object> taskList = new LinkedHashMap<>();
        taskList.put("userGoal", plan.getUserGoal());
        taskList.put("planId", plan.getPlanId() != null ? plan.getPlanId() : draftId);
        taskList.put("tasks", plan.getSteps().stream().map(this::toTaskItem).toList());
        taskList.put("status", plan.getStatus());
        taskList.put("currentTaskIndex", plan.getCurrentStepIndex());
        taskList.put("taskOutputs", plan.getStepOutputs() != null ? plan.getStepOutputs() : Map.of());
        taskList.put("createdAt", plan.getCreatedAt() != null ? plan.getCreatedAt() : System.currentTimeMillis());
        taskList.put("clarificationQuestion", plan.getClarificationQuestion() != null ? plan.getClarificationQuestion() : "");
        taskList.put("confirmationDraftId", draftId);
        return taskList;
    }

    private Map<String, Object> toTaskItem(ExecutionStep step) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", step.getStepId());
        task.put("description", step.getDescription());
        task.put("requiredTools", step.getRequiredTools() != null ? step.getRequiredTools() : List.of());
        task.put("dependsOn", step.getDependsOn() != null ? step.getDependsOn() : List.of());
        task.put("gate", step.getGate());
        task.put("riskLevel", step.getRiskLevel());
        task.put("expectedOutput", step.getExpectedOutput() != null ? step.getExpectedOutput() : "");
        task.put("status", step.getStatus());
        task.put("resultSummary", step.getResultSummary() != null ? step.getResultSummary() : "");
        task.put("errorMessage", step.getErrorMessage() != null ? step.getErrorMessage() : "");
        task.put("retryCount", step.getRetryCount());
        return task;
    }

    private String buildPlanSummaryText(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("我已为你规划了完成「").append(plan.getUserGoal()).append("」的任务计划，共 ")
                .append(plan.getSteps().size()).append(" 个步骤：\n\n");

        for (int i = 0; i < plan.getSteps().size(); i++) {
            ExecutionStep task = plan.getSteps().get(i);
            sb.append(i + 1).append(". ").append(task.getDescription());
            if ("CLARIFICATION".equals(task.getGate())) {
                sb.append("（需要你提供信息）");
            } else if ("CONFIRMATION".equals(task.getGate())) {
                sb.append("（需要你确认）");
            }
            sb.append("\n");
        }

        sb.append("\n请确认是否开始执行？");
        return sb.toString();
    }

    private String buildConfirmationText(ExecutionStep task, ExecutionPlan plan) {
        return "即将执行：" + task.getDescription() + "\n请确认是否继续？";
    }

    private String buildFinalSummary(ExecutionPlan plan) {
        long completed = plan.getSteps().stream().filter(t -> "COMPLETED".equals(t.getStatus())).count();
        long failed = plan.getSteps().stream().filter(t -> "FAILED".equals(t.getStatus())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("任务计划执行完成！共 ").append(plan.getSteps().size())
                .append(" 个任务，成功 ").append(completed).append(" 个");
        if (failed > 0) {
            sb.append("，失败 ").append(failed).append(" 个");
        }
        sb.append("。\n");

        for (ExecutionStep task : plan.getSteps()) {
            String icon = switch (task.getStatus()) {
                case "COMPLETED" -> "✓";
                case "FAILED" -> "✗";
                default -> "○";
            };
            sb.append(icon).append(" ").append(task.getStepId()).append(": ").append(task.getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 发射执行完成状态事件到 trace 面板，不在 AI 对话正文中展示。
     */
    private void emitExecutionCompletedStatus(ExecutionPlan plan, AgentRuntimeContext context,
                                              SseEmitter emitter) {
        long completed = plan.getSteps().stream().filter(t -> "COMPLETED".equals(t.getStatus())).count();
        long failed = plan.getSteps().stream().filter(t -> "FAILED".equals(t.getStatus())).count();

        Map<String, Object> statusPayload = new LinkedHashMap<>();
        statusPayload.put("type", "plan_completed");
        statusPayload.put("planStatus", plan.getStatus());
        statusPayload.put("totalTasks", plan.getSteps().size());
        statusPayload.put("completedTasks", completed);
        statusPayload.put("failedTasks", failed);

        // 附加每个任务的摘要（用于 trace 面板展示）
        List<Map<String, String>> taskSummaries = plan.getSteps().stream()
                .map(task -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("stepId", task.getStepId());
                    m.put("status", task.getStatus());
                    m.put("description", task.getDescription());
                    if (task.getErrorMessage() != null) m.put("errorMessage", task.getErrorMessage());
                    return m;
                })
                .toList();
        statusPayload.put("taskSummaries", taskSummaries);

        dispatcher.emit(emitter, context, AgentEventType.STATUS, statusPayload);
    }

    /**
     * 生成面向用户友好的计划完成消息（替代 buildFinalSummary 作为 TEXT_DELTA 正文）。
     */
    private String buildUserFriendlyCompletionMessage(ExecutionPlan plan) {
        long completed = plan.getSteps().stream().filter(t -> "COMPLETED".equals(t.getStatus())).count();
        long failed = plan.getSteps().stream().filter(t -> "FAILED".equals(t.getStatus())).count();
        long total = plan.getSteps().size();

        if (failed == 0 && completed == total) {
            return "学习计划已全部执行完成！你可以查看上述结果，或告诉我下一步想做什么。";
        }
        if (completed > 0 && failed > 0) {
            long skipped = plan.getSteps().stream().filter(t -> "SKIPPED".equals(t.getStatus())).count();
            StringBuilder sb = new StringBuilder();
            sb.append("学习计划已执行完成。");
            if (completed > 0) sb.append("成功 ").append(completed).append(" 个任务");
            if (failed > 0) {
                sb.append("，").append(failed).append(" 个任务未成功");
            }
            if (skipped > 0) {
                sb.append("（其中 ").append(skipped).append(" 个已跳过）");
            }
            sb.append("。\n\n你可以让我重试失败的任务，或者查看详情后调整计划。");
            return sb.toString();
        }
        if (completed == 0 && failed == total) {
            return "很抱歉，学习计划中的所有任务都未能成功完成。建议你调整目标或提供更多信息后重试。";
        }
        // 兜底
        return "学习计划执行结束。";
    }

    /**
     * 确保 askUser 工具在所有 Plan-and-Execute 任务中可用。
     * LLM 可在任何需要向用户澄清信息时调用此工具。
     */
    private List<String> mergeWithAskUser(List<String> taskTools) {
        if (taskTools == null || taskTools.isEmpty()) {
            return List.of("askUser");
        }
        if (taskTools.contains("askUser")) {
            return taskTools;
        }
        List<String> merged = new java.util.ArrayList<>(taskTools);
        merged.add("askUser");
        return merged;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
