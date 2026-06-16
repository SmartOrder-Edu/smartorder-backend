package com.smartorder.edu.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.clarification.ClarificationService;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.ClarificationPayload;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
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
            dispatcher.emit(emitter, AgentRuntimeContext.builder().traceId(traceId).build(),
                    AgentEventType.ERROR, Map.of("message", "规划草稿不存在"));
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
                .sequence(draft.getResumeStepIndex() != null
                        ? new java.util.concurrent.atomic.AtomicInteger(draft.getResumeStepIndex() * 100)
                        : new java.util.concurrent.atomic.AtomicInteger(0))
                .build();

        if (resumed) {
            emitResumeIfConfirmationWasCleared(plan, context, emitter);
        }

        executeExecutionPlanInternal(draft, plan, context, emitter, draftId,
                userId, conversationId, resumed);
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
                .sequence(new java.util.concurrent.atomic.AtomicInteger(
                        parentContext.getSequence() != null ? parentContext.getSequence().get() : 0))
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
            boolean taskCompleted = executeAndVerifyTask(task, stepOutputs, plan, draftId,
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
        } else if (hasFailed && !hasCompleted) {
            plan.setStatus("FAILED");
        } else {
            plan.setStatus("COMPLETED");
        }
        draftService.updateExecutionPlan(draftId, plan);

        String finalSummary = buildFinalSummary(plan);
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", finalSummary));
        memoryService.saveAssistantMessage(conversationId, userId,
                finalSummary, null, context.getTraceId(), null);
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
     */
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

            plan.setStepOutputs(stepOutputs);
            pausedTask.setStatus("COMPLETED");
            pausedTask.setResultSummary("用户回复: " + truncate(request.getMessage(), 200));
            pausedTask.setGate("NORMAL"); // 清除门

            plan.setCurrentStepIndex(pausedIndex + 1);
            plan.setStatus("EXECUTING");
            plan.setActiveClarificationId(null);
            applyClarificationToRemainingSteps(plan, pausedIndex, stepOutputs);

            draftService.updateExecutionPlan(draft.getDraftId(), plan);

            dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED,
                    planCreatedPayload(draft.getDraftId(), plan, false, ""));

            dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                    "reason", "CLARIFICATION",
                    "taskId", pausedTask.getStepId(),
                    "resumeFromIndex", plan.getCurrentStepIndex()
            ));

            executeExecutionPlanWithContext(draft.getDraftId(), context.getUserId(),
                    context, emitter);

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
        }
    }

    // ============ 私有方法 ============

    private String buildPlannerPrompt(AgentChatRequest request, IntentRouteResult routeResult,
                                       AgentRuntimeContext context) {
        String attachmentSection = "";
        if (context.getAttachmentContext() != null && !context.getAttachmentContext().isBlank()) {
            attachmentSection = "\n用户上传附件摘录：\n" + context.getAttachmentContext();
        }

        String planHint = routeResult.getPlanHint() != null ? routeResult.getPlanHint() : "general";

        return PromptLoader.format(promptLoader.getPlannerInputTemplate(),
                request.getMessage(), planHint, attachmentSection);
    }

    private void applyClarificationToRemainingSteps(ExecutionPlan plan, int pausedIndex,
                                                     Map<String, String> stepOutputs) {
        // 从 stepOutputs 中提取结构化澄清上下文
        StringBuilder clarificationSummary = new StringBuilder();
        Map<String, String> clarificationContext = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stepOutputs.entrySet()) {
            if (entry.getKey().startsWith("__clarification.")) {
                String slotKey = entry.getKey().substring("__clarification.".length());
                clarificationContext.put(slotKey, entry.getValue());
                clarificationSummary.append(slotKey).append(": ").append(entry.getValue()).append("; ");
            }
        }

        // 兼容旧的自由文本澄清
        String legacyClarification = stepOutputs.get("__userClarification");
        String summaryText = clarificationSummary.length() > 0
                ? clarificationSummary.toString()
                : (legacyClarification != null ? truncate(legacyClarification, 300) : "");

        for (int i = pausedIndex + 1; i < plan.getSteps().size(); i++) {
            ExecutionStep step = plan.getSteps().get(i);
            if ("COMPLETED".equals(step.getStatus())) {
                continue;
            }

            String description = step.getDescription();
            if (description != null && description.contains("学生画像")) {
                description = description.replace("学生画像", "用户补充信息");
            }
            if (description != null && description.contains("学习档案")) {
                description = description.replace("学习档案", "用户补充信息");
            }

            if (description != null && !description.contains("用户补充信息")) {
                description = description + "。请结合用户补充信息：" + summaryText;
            } else if (description != null && !description.contains(summaryText)) {
                description = description + "（用户补充信息：" + summaryText + "）";
            }

            step.setDescription(description);

            List<String> tools = step.getRequiredTools();
            if (tools != null && tools.contains("getStudentProfile")) {
                step.setRequiredTools(tools.stream()
                        .filter(tool -> !"getStudentProfile".equals(tool))
                        .toList());
            }
        }
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
        if (draftId == null || draftId.isBlank()) {
            draftId = draftService.saveExecutionPlanDraft(
                    context.getConversationId(), context.getTraceId(),
                    request != null ? request.getUserId() : context.getUserId(), plan);
        }
        plan.setConfirmationDraftId(draftId);
        draftService.updateExecutionPlan(draftId, plan);

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

            executeAndVerifyTask(task, stepOutputs, plan, draftId, context, emitter,
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
        // 1. 构建任务专属系统消息
        String taskSystemMessage = buildTaskSystemMessage(task, previousOutputs);

        // 2. 构建受限的 IntentRouteResult（只包含本任务需要的工具）
        IntentRouteResult taskRoute = IntentRouteResult.builder()
                .intentType("task_execution")
                .intentEnum(IntentType.TASK_MANAGEMENT)
                .runMode(AgentRunMode.REACT)
                .requiredTools(task.getRequiredTools())
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
        StringBuilder prevSb = new StringBuilder();
        if (!previousOutputs.isEmpty()) {
            prevSb.append("\n前置任务结果：\n");
            for (Map.Entry<String, String> entry : previousOutputs.entrySet()) {
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

    // ============ 验证与重规划 ============

    /**
     * 执行单个任务并进行质量验证，验证失败时自动重试（最多 MAX_RETRIES_PER_TASK 次）
     *
     * @return true 表示任务最终通过验证，false 表示需要外部重规划
     */
    private boolean executeAndVerifyTask(ExecutionStep task, Map<String, String> stepOutputs,
                                          ExecutionPlan plan, String draftId,
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

                result = executeTaskViaReAct(task, stepOutputs, context, emitter, confirmationGranted);
            } catch (Exception e) {
                log.error("[PlanAndExecute] 任务执行异常: taskId={}", task.getStepId(), e);
                task.setErrorMessage(e.getMessage());
                dispatcher.emit(emitter, context, AgentEventType.EXECUTION_STEP_FAILED, stepToPayload(task));
                // 执行异常直接算失败，不重试
                return false;
            }

            if (shouldPauseForProfileClarification(task, result)) {
                log.info("[PlanAndExecute] 学生画像不可用，转入澄清门: taskId={}", task.getStepId());
                pauseForProfileClarification(plan, task, context, emitter, draftId);
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

    private boolean shouldPauseForProfileClarification(ExecutionStep task, String result) {
        if (task.getRequiredTools() == null || !task.getRequiredTools().contains("getStudentProfile")) {
            return false;
        }
        if (result == null || result.isBlank()) {
            return false;
        }

        String normalized = result.toLowerCase(Locale.ROOT);
        return normalized.contains("尚未完成画像建档")
                || normalized.contains("暂无学习偏好")
                || normalized.contains("暂无学习档案")
                || normalized.contains("无法获取学生的学习档案")
                || normalized.contains("暂时无法获取")
                || normalized.contains("通过对话")
                || normalized.contains("直接了解学生的学习目标和当前水平")
                || normalized.contains("直接告诉我你的学习目标和当前水平")
                || (normalized.contains("补充")
                        && (normalized.contains("当前基础") || normalized.contains("当前水平"))
                        && (normalized.contains("截止时间") || normalized.contains("可投入时间")))
                || normalized.contains("profile unavailable")
                || normalized.contains("no profile");
    }

    private void pauseForProfileClarification(ExecutionPlan plan, ExecutionStep task,
                                              AgentRuntimeContext context, SseEmitter emitter,
                                              String draftId) {
        // 构建结构化澄清槽位（一次只问一个，按优先级排序）
        String goal = plan.getUserGoal();
        String goalPrefix = goal != null && !goal.isBlank()
                ? "为了给「" + goal + "」制定更贴合的计划，" : "为了制定更贴合的计划，";

        List<ExecutionStep.ClarificationSlot> slots = List.of(
                ExecutionStep.ClarificationSlot.builder()
                        .slotKey("target_scope")
                        .question(goalPrefix + "你的学习目标具体是什么？")
                        .answerType("SINGLE_SELECT")
                        .options(List.of(
                                ClarificationPayload.SelectOption.builder().label("高考备考").value("高考").build(),
                                ClarificationPayload.SelectOption.builder().label("中考备考").value("中考").build(),
                                ClarificationPayload.SelectOption.builder().label("日常提升").value("日常提升").build(),
                                ClarificationPayload.SelectOption.builder().label("竞赛冲刺").value("竞赛").build(),
                                ClarificationPayload.SelectOption.builder().label("课程学习").value("课程").build()
                        ))
                        .slotPriority(1)
                        .build(),
                ExecutionStep.ClarificationSlot.builder()
                        .slotKey("current_level")
                        .question(goalPrefix + "你现在在这个目标上的基础大概是什么水平？")
                        .answerType("SINGLE_SELECT")
                        .options(List.of(
                                ClarificationPayload.SelectOption.builder().label("零基础").value("零基础").build(),
                                ClarificationPayload.SelectOption.builder().label("学过但不熟").value("学过但不熟").build(),
                                ClarificationPayload.SelectOption.builder().label("能做基础题").value("能做基础题").build(),
                                ClarificationPayload.SelectOption.builder().label("有项目/竞赛经验").value("有项目/竞赛经验").build()
                        ))
                        .slotPriority(2)
                        .build(),
                ExecutionStep.ClarificationSlot.builder()
                        .slotKey("deadline_or_duration")
                        .question(goalPrefix + "你希望多久完成，或有没有截止时间？")
                        .answerType("TEXT")
                        .slotPriority(3)
                        .build(),
                ExecutionStep.ClarificationSlot.builder()
                        .slotKey("weekly_time_budget")
                        .question("每周大概能投入多少时间学习？")
                        .answerType("TEXT")
                        .slotPriority(4)
                        .build(),
                ExecutionStep.ClarificationSlot.builder()
                        .slotKey("preference")
                        .question("你偏好哪种学习方式？（可选）")
                        .answerType("SINGLE_SELECT")
                        .options(List.of(
                                ClarificationPayload.SelectOption.builder().label("视频为主").value("视频").build(),
                                ClarificationPayload.SelectOption.builder().label("书籍为主").value("书籍").build(),
                                ClarificationPayload.SelectOption.builder().label("题目训练为主").value("题目训练").build(),
                                ClarificationPayload.SelectOption.builder().label("项目实践为主").value("项目实践").build()
                        ))
                        .slotPriority(5)
                        .build()
        );

        task.setGate("CLARIFICATION");
        task.setClarificationSlots(slots);
        task.setClarificationSlotsJson(JSON.toJSONString(slots));
        task.setDescription(slots.get(0).getQuestion()); // 第一个 slot 的问题作为步骤描述
        task.setStatus("PAUSED");
        task.setErrorMessage("学生画像不可用，等待用户补充关键信息");
        handleClarificationGate(plan, plan.getSteps().indexOf(task), context, emitter, null, draftId);
    }

    private String buildProfileClarificationQuestion(ExecutionPlan plan) {
        String goal = plan.getUserGoal();
        String prefix = goal != null && !goal.isBlank()
                ? "我暂时拿不到你的学习档案。为了给「" + goal + "」制定更贴合的计划，"
                : "我暂时拿不到你的学习档案。为了制定更贴合的计划，";
        return prefix + "请补充这几项信息：当前基础或水平、目标/考试范围、截止时间、每周可投入时间，以及偏好的学习方式。";
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

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
