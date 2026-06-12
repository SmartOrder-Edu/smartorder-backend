package com.smartorder.edu.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.TaskItem;
import com.smartorder.edu.agent.model.dto.TaskList;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.model.dto.VerificationResult;
import com.smartorder.edu.agent.model.dto.VerificationResult.SuggestedAction;
import com.smartorder.edu.agent.plan.LLMStepVerifier;
import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.plan.PlanSchemaValidator;
import com.smartorder.edu.agent.plan.PlannerAgent;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.route.IntentType;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolRegistry;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
    private final ToolRegistry toolRegistry;
    private final LangChain4jToolAdapter toolAdapter;
    private final PlanSchemaValidator schemaValidator;
    private final PlanDraftService draftService;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;
    private final SelfManagedReActEngine reActEngine;
    private final LLMStepVerifier stepVerifier;

    /** 单任务最大重试次数 */
    private static final int MAX_RETRIES_PER_TASK = 3;
    /** 单次计划最大重规划次数 */
    private static final int MAX_TOTAL_REPLANS = 5;

    // ============ 公开 API ============

    /**
     * Phase 1: 规划——生成 TaskList
     */
    public void runPlanning(AgentChatRequest request, IntentRouteResult routeResult,
                            AgentRuntimeContext context, SseEmitter emitter) {
        // 1. 发射阶段变更
        dispatcher.emit(emitter, context, AgentEventType.PHASE_CHANGE, Map.of(
                "phase", "PLANNING",
                "message", "正在分析目标并生成任务计划"
        ));

        // 2. 预取学生画像
        String profileJson = preFetchProfile(request, context, emitter);

        // 3. 构建 PlannerAgent prompt
        String prompt = buildPlannerPrompt(request, routeResult, profileJson, context);

        // 4. 调用 PlannerAgent 生成 TaskList
        String taskListRaw;
        try {
            PlannerAgent plannerAgent = AiServices.builder(PlannerAgent.class)
                    .chatModel(chatLanguageModel)
                    .build();
            taskListRaw = plannerAgent.createTaskList(prompt);
        } catch (Exception e) {
            log.error("[PlanAndExecute] PlannerAgent 调用失败", e);
            String errorMsg = "抱歉，生成任务计划时遇到了问题，请稍后再试。";
            dispatcher.emit(emitter, context, AgentEventType.ERROR, Map.of(
                    "message", "规划生成失败: " + e.getMessage()
            ));
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", errorMsg));
            memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                    errorMsg, null, context.getTraceId(), null);
            return;
        }

        // 5. 校验 TaskList
        TaskList taskList;
        try {
            taskList = schemaValidator.validateAndParseTaskList(taskListRaw);
        } catch (Exception e) {
            log.error("[PlanAndExecute] TaskList 校验失败: {}", e.getMessage());
            String errorMsg = "生成的任务计划格式不正确，请重新描述你的目标。";
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", errorMsg));
            memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                    errorMsg, null, context.getTraceId(), null);
            return;
        }

        // 6. 初始化 TaskList 元数据
        taskList.setStatus("DRAFT");

        // 7. 检查第一个 task 是否是 CLARIFICATION gate → 立即处理
        TaskItem firstTask = taskList.getTasks().get(0);
        if ("CLARIFICATION".equals(firstTask.getGate())) {
            handleClarificationGate(taskList, 0, context, emitter, request);
            return;
        }

        // 8. 检查是否有 CONFIRMATION gate → 保存草稿并暂停
        boolean hasConfirmationGate = taskList.getTasks().stream()
                .anyMatch(t -> "CONFIRMATION".equals(t.getGate()));

        if (hasConfirmationGate) {
            // 先执行 CONFIRMATION gate 之前的 NORMAL 任务
            int firstConfirmationIndex = findFirstConfirmationIndex(taskList);
            if (firstConfirmationIndex > 0) {
                // 有前置 NORMAL 任务，先执行它们
                taskList.setStatus("EXECUTING");
                String draftId = draftService.saveTaskListDraft(
                        context.getConversationId(), context.getTraceId(),
                        request.getUserId(), taskList);

                executeTasksUntilGate(draftId, taskList, firstConfirmationIndex, context, emitter, request);
                return;
            }

            // 第一个任务就是 CONFIRMATION，直接暂停
            String draftId = draftService.saveTaskListDraft(
                    context.getConversationId(), context.getTraceId(),
                    request.getUserId(), taskList);

            taskList.setStatus("PAUSED_CONFIRMATION");
            taskList.setConfirmationDraftId(draftId);
            draftService.updateTaskList(draftId, taskList);

            // 发射 PLAN_CREATED 事件
            dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED, Map.of(
                    "draftId", draftId,
                    "taskList", taskList,
                    "needsConfirmation", true,
                    "clarificationQuestion", ""
            ));

            // 输出摘要
            String summaryText = buildPlanSummaryText(taskList);
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", summaryText));
            memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                    summaryText, null, context.getTraceId(), null);
            return;
        }

        // 9. 无门 → 保存草稿，直接执行
        String draftId = draftService.saveTaskListDraft(
                context.getConversationId(), context.getTraceId(),
                request.getUserId(), taskList);
        taskList.setStatus("EXECUTING");
        draftService.updateTaskList(draftId, taskList);

        executeTaskList(draftId, request.getUserId(), context.getConversationId(),
                emitter, context.getTraceId());
    }

    /**
     * Phase 2: 执行 TaskList（确认后调用或无门直接调用）
     */
    public void executeTaskList(String draftId, Long userId, String conversationId,
                                SseEmitter emitter, String traceId) {
        // 1. 从 DB 加载 TaskList
        AgentPlanDraft draft = draftService.getByDraftId(draftId);
        if (draft == null) {
            log.error("[PlanAndExecute] 草稿不存在: {}", draftId);
            dispatcher.emit(emitter, AgentRuntimeContext.builder().traceId(traceId).build(),
                    AgentEventType.ERROR, Map.of("message", "规划草稿不存在"));
            return;
        }

        TaskList taskList = JSON.parseObject(draft.getTaskListJson(), TaskList.class);

        // 2. 构建运行时上下文
        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .sequence(draft.getResumeTaskIndex() != null
                        ? new java.util.concurrent.atomic.AtomicInteger(draft.getResumeTaskIndex() * 100)
                        : new java.util.concurrent.atomic.AtomicInteger(0))
                .build();

        // 3. 拓扑排序
        List<TaskItem> sortedTasks = topologicalSort(taskList.getTasks());

        // 4. 执行任务（带验证+动态重规划）
        Map<String, String> taskOutputs = new LinkedHashMap<>(taskList.getTaskOutputs());
        taskList.setStatus("EXECUTING");

        int totalReplans = 0;
        int taskIndex = taskList.getCurrentTaskIndex();

        while (taskIndex < sortedTasks.size()) {
            TaskItem task = sortedTasks.get(taskIndex);

            // 跳过已完成的任务
            if ("COMPLETED".equals(task.getStatus())) {
                taskIndex++;
                continue;
            }

            // 检查依赖
            if (!allDependenciesCompleted(task, taskOutputs)) {
                task.setStatus("FAILED");
                task.setErrorMessage("依赖任务未完成: " + task.getDependsOn());
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED, taskToPayload(task));
                taskIndex++;
                continue;
            }

            // 根据门类型分发（门任务不受验证循环控制）
            if ("CLARIFICATION".equals(task.getGate())) {
                task.setStatus("RUNNING");
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_STARTED, taskToPayload(task));
                handleClarificationGate(taskList, taskIndex, context, emitter, null);
                taskList.setCurrentTaskIndex(taskIndex);
                draftService.updateTaskList(draftId, taskList);
                return;
            }

            if ("CONFIRMATION".equals(task.getGate())) {
                task.setStatus("RUNNING");
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_STARTED, taskToPayload(task));
                taskList.setCurrentTaskIndex(taskIndex);
                taskList.setStatus("PAUSED_CONFIRMATION");
                taskList.setConfirmationDraftId(draftId);
                draftService.updateTaskList(draftId, taskList);

                dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED, Map.of(
                        "draftId", draftId,
                        "taskList", taskList,
                        "needsConfirmation", true,
                        "clarificationQuestion", ""
                ));

                String confirmText = buildConfirmationText(task, taskList);
                dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", confirmText));
                memoryService.saveAssistantMessage(conversationId, userId, confirmText, null, traceId, null);
                return;
            }

            // NORMAL 任务：执行 → 验证 → 重试/重规划 循环
            boolean taskCompleted = executeAndVerifyTask(task, taskOutputs, taskList, draftId,
                    context, emitter, conversationId, userId);

            if (!taskCompleted) {
                // 任务验证失败，尝试重规划剩余任务
                if (totalReplans >= MAX_TOTAL_REPLANS) {
                    log.warn("[PlanAndExecute] 达到最大重规划次数 {}, 终止计划", MAX_TOTAL_REPLANS);
                    task.setStatus("FAILED");
                    task.setErrorMessage("超出最大重规划次数限制");
                    dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED, taskToPayload(task));
                    break;
                }

                totalReplans++;
                boolean replanned = replanRemainingTasks(taskList, sortedTasks, taskIndex,
                        task, taskOutputs, context, emitter, draftId);
                if (replanned) {
                    // 重新拓扑排序，从当前索引继续
                    sortedTasks = topologicalSort(taskList.getTasks());
                    // taskIndex 保持不变，继续执行重规划后的新任务
                    dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                            "type", "plan_replanned",
                            "message", "计划已根据执行反馈动态调整",
                            "taskIndex", taskIndex,
                            "remainingTasks", sortedTasks.size() - taskIndex,
                            "totalReplans", totalReplans
                    ));
                } else {
                    // 无法重规划，跳过当前任务
                    log.warn("[PlanAndExecute] 重规划失败，跳过任务: taskId={}", task.getTaskId());
                    taskIndex++;
                }
            } else {
                taskIndex++;
            }

            // 更新 DB
            taskList.setCurrentTaskIndex(taskIndex);
            draftService.updateTaskList(draftId, taskList);
        }

        // 5. 全部完成
        taskList.setStatus("COMPLETED");
        draftService.updateTaskList(draftId, taskList);

        String finalSummary = buildFinalSummary(taskList);
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", finalSummary));
        memoryService.saveAssistantMessage(conversationId, userId,
                finalSummary, null, traceId, null);
    }

    /**
     * 恢复暂停的计划（澄清回复 / 确认词）
     */
    public void resumePlan(AgentChatRequest request, AgentRuntimeContext context, SseEmitter emitter) {
        AgentPlanDraft draft = draftService.getActiveTaskListDraft(context.getConversationId());
        if (draft == null) {
            log.warn("[PlanAndExecute] 未找到活跃的 TaskList 草稿: convId={}", context.getConversationId());
            return;
        }

        TaskList taskList = JSON.parseObject(draft.getTaskListJson(), TaskList.class);

        if ("PAUSED_CLARIFICATION".equals(taskList.getStatus())) {
            // 用户提供了澄清回复
            int pausedIndex = taskList.getCurrentTaskIndex();
            TaskItem pausedTask = taskList.getTasks().get(pausedIndex);

            // 将用户回复注入任务输出
            String userResponse = request.getMessage();
            taskList.getTaskOutputs().put(pausedTask.getTaskId(), "用户澄清: " + userResponse);
            pausedTask.setStatus("COMPLETED");
            pausedTask.setResultSummary("用户回复: " + truncate(userResponse, 200));
            pausedTask.setGate("NORMAL"); // 清除门

            taskList.setCurrentTaskIndex(pausedIndex + 1);
            taskList.setStatus("EXECUTING");

            dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                    "reason", "CLARIFICATION",
                    "taskId", pausedTask.getTaskId(),
                    "resumeFromIndex", taskList.getCurrentTaskIndex()
            ));

            draftService.updateTaskList(draft.getDraftId(), taskList);
            executeTaskList(draft.getDraftId(), context.getUserId(),
                    context.getConversationId(), emitter, context.getTraceId());

        } else if ("PAUSED_CONFIRMATION".equals(taskList.getStatus())) {
            // 用户通过聊天确认词确认（前端按钮确认走 /agent/plan/confirm）
            int pausedIndex = taskList.getCurrentTaskIndex();
            TaskItem pausedTask = taskList.getTasks().get(pausedIndex);
            pausedTask.setGate("NORMAL"); // 清除确认门

            taskList.setStatus("EXECUTING");
            draftService.updateTaskList(draft.getDraftId(), taskList);

            dispatcher.emit(emitter, context, AgentEventType.PLAN_RESUMED, Map.of(
                    "reason", "CONFIRMATION",
                    "taskId", pausedTask.getTaskId()
            ));

            executeTaskList(draft.getDraftId(), context.getUserId(),
                    context.getConversationId(), emitter, context.getTraceId());
        }
    }

    // ============ 私有方法 ============

    private String preFetchProfile(AgentChatRequest request, AgentRuntimeContext context, SseEmitter emitter) {
        if (!toolRegistry.contains("getStudentProfile")) {
            return "{}";
        }
        try {
            ToolInvokeContext toolCtx = ToolInvokeContext.builder()
                    .userId(request.getUserId())
                    .conversationId(context.getConversationId())
                    .traceId(context.getTraceId())
                    .runtimeContext(context)
                    .emitter(emitter)
                    .build();

            ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                    .name("getStudentProfile")
                    .arguments("{}")
                    .build();

            return toolAdapter.execute(toolReq, toolCtx);
        } catch (Exception e) {
            log.warn("[PlanAndExecute] 获取学生画像失败，继续生成规划: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildPlannerPrompt(AgentChatRequest request, IntentRouteResult routeResult,
                                       String profileJson, AgentRuntimeContext context) {
        String attachmentSection = "";
        if (context.getAttachmentContext() != null && !context.getAttachmentContext().isBlank()) {
            attachmentSection = "\n用户上传附件摘录：\n" + context.getAttachmentContext();
        }

        String planHint = routeResult.getPlanHint() != null ? routeResult.getPlanHint() : "general";

        return """
                用户目标：%s
                学生画像：%s
                路由提示（意图类型）：%s
                %s

                请生成任务列表。
                """.formatted(request.getMessage(), profileJson, planHint, attachmentSection);
    }

    private void handleClarificationGate(TaskList taskList, int taskIndex,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          AgentChatRequest request) {
        TaskItem task = taskList.getTasks().get(taskIndex);
        String question = task.getDescription();

        taskList.setStatus("PAUSED_CLARIFICATION");
        taskList.setClarificationQuestion(question);
        taskList.setCurrentTaskIndex(taskIndex);

        // 保存草稿
        String draftId = draftService.saveTaskListDraft(
                context.getConversationId(), context.getTraceId(),
                request != null ? request.getUserId() : context.getUserId(), taskList);
        taskList.setConfirmationDraftId(draftId);
        draftService.updateTaskList(draftId, taskList);

        // 发射暂停事件
        dispatcher.emit(emitter, context, AgentEventType.PLAN_PAUSED, Map.of(
                "reason", "CLARIFICATION",
                "taskId", task.getTaskId(),
                "question", question
        ));

        // 发射澄清文本
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", question));
        memoryService.saveAssistantMessage(context.getConversationId(),
                request != null ? request.getUserId() : context.getUserId(),
                question, null, context.getTraceId(), null);
    }

    /**
     * 执行任务直到遇到确认门（执行 CONFIRMATION 之前的 NORMAL 任务），带验证
     */
    private void executeTasksUntilGate(String draftId, TaskList taskList, int gateIndex,
                                        AgentRuntimeContext context, SseEmitter emitter,
                                        AgentChatRequest request) {
        Map<String, String> taskOutputs = new LinkedHashMap<>(taskList.getTaskOutputs());
        String conversationId = context.getConversationId();
        Long userId = request != null ? request.getUserId() : context.getUserId();

        for (int i = 0; i < gateIndex; i++) {
            TaskItem task = taskList.getTasks().get(i);

            if ("COMPLETED".equals(task.getStatus())) continue;
            if (!"NORMAL".equals(task.getGate())) continue;

            executeAndVerifyTask(task, taskOutputs, taskList, draftId, context, emitter,
                    conversationId, userId);

            taskList.setCurrentTaskIndex(i + 1);
            draftService.updateTaskList(draftId, taskList);
        }

        // 执行到确认门前，暂停
        taskList.setStatus("PAUSED_CONFIRMATION");
        taskList.setConfirmationDraftId(draftId);
        draftService.updateTaskList(draftId, taskList);

        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED, Map.of(
                "draftId", draftId,
                "taskList", taskList,
                "needsConfirmation", true,
                "clarificationQuestion", ""
        ));

        String summaryText = buildPlanSummaryText(taskList);
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", summaryText));
        memoryService.saveAssistantMessage(context.getConversationId(), request.getUserId(),
                summaryText, null, context.getTraceId(), null);
    }

    /**
     * 通过 ReAct 引擎执行单个任务
     */
    private String executeTaskViaReAct(TaskItem task, Map<String, String> previousOutputs,
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

        // 4. 通过 ReAct 引擎执行（带任务上下文），直接返回执行结果
        return reActEngine.executeWithTaskContext(taskRequest, taskRoute, context, emitter,
                taskSystemMessage, previousOutputs, confirmationGranted);
    }

    private String buildTaskSystemMessage(TaskItem task, Map<String, String> previousOutputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你正在执行学习计划中的任务 [").append(task.getTaskId()).append("]。\n");
        sb.append("任务描述：").append(task.getDescription()).append("\n");
        sb.append("预期产出：").append(task.getExpectedOutput() != null ? task.getExpectedOutput() : "根据任务完成").append("\n");

        if (!previousOutputs.isEmpty()) {
            sb.append("\n前置任务结果：\n");
            for (Map.Entry<String, String> entry : previousOutputs.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ")
                        .append(truncate(entry.getValue(), 300)).append("\n");
            }
        }

        return sb.toString();
    }

    // ============ 验证与重规划 ============

    /**
     * 执行单个任务并进行质量验证，验证失败时自动重试（最多 MAX_RETRIES_PER_TASK 次）
     *
     * @return true 表示任务最终通过验证，false 表示需要外部重规划
     */
    private boolean executeAndVerifyTask(TaskItem task, Map<String, String> taskOutputs,
                                          TaskList taskList, String draftId,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          String conversationId, Long userId) {
        boolean confirmationGranted = "WRITE_CONFIRMED".equals(task.getRiskLevel());

        for (int attempt = 0; attempt <= MAX_RETRIES_PER_TASK; attempt++) {
            task.setStatus("RUNNING");
            dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_STARTED, taskToPayload(task));

            String result;
            try {
                if (attempt > 0) {
                    // 重试时使用修正后的描述
                    log.info("[PlanAndExecute] 重试任务: taskId={}, attempt={}/{}, revisedDesc={}",
                            task.getTaskId(), attempt, MAX_RETRIES_PER_TASK, task.getDescription());
                    dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                            "type", "task_retry",
                            "taskId", task.getTaskId(),
                            "attempt", attempt,
                            "maxRetries", MAX_RETRIES_PER_TASK
                    ));
                }

                result = executeTaskViaReAct(task, taskOutputs, context, emitter, confirmationGranted);
            } catch (Exception e) {
                log.error("[PlanAndExecute] 任务执行异常: taskId={}", task.getTaskId(), e);
                task.setErrorMessage(e.getMessage());
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED, taskToPayload(task));
                // 执行异常直接算失败，不重试
                return false;
            }

            // 验证结果质量
            VerificationResult verification = stepVerifier.verify(task, result, taskOutputs);

            if (verification.isPassed()) {
                task.setStatus("COMPLETED");
                task.setResultSummary(truncate(result, 500));
                taskOutputs.put(task.getTaskId(), truncate(result, 2000));
                taskList.setTaskOutputs(taskOutputs);
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_COMPLETED, taskToPayload(task));
                log.info("[PlanAndExecute] 任务验证通过: taskId={}", task.getTaskId());
                return true;
            }

            // 验证失败，根据建议动作处理
            log.warn("[PlanAndExecute] 任务验证失败: taskId={}, action={}, feedback={}",
                    task.getTaskId(), verification.getAction(),
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
                        log.warn("[PlanAndExecute] 任务重试次数耗尽: taskId={}", task.getTaskId());
                        return false; // 需要重规划
                    }
                }
                case REPLAN -> {
                    return false; // 需要重规划
                }
                case SKIP -> {
                    task.setStatus("SKIPPED");
                    task.setErrorMessage("验证未通过，已跳过: " + verification.getFeedback());
                    dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED, taskToPayload(task));
                    return true; // 跳过视为完成（不阻塞后续任务）
                }
                case CLARIFY -> {
                    // 转为澄清门，暂停执行
                    task.setGate("CLARIFICATION");
                    task.setDescription(verification.getFeedback());
                    task.setStatus("PAUSED");
                    handleClarificationGate(taskList,
                            taskList.getTasks().indexOf(task), context, emitter, null);
                    return false; // 暂停
                }
                default -> { // CONTINUE (should not happen when !passed)
                    task.setStatus("COMPLETED");
                    task.setResultSummary(truncate(result, 500));
                    taskOutputs.put(task.getTaskId(), truncate(result, 2000));
                    taskList.setTaskOutputs(taskOutputs);
                    dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_COMPLETED, taskToPayload(task));
                    return true;
                }
            }
        }

        return false; // 重试耗尽
    }

    /**
     * 调用 PlannerAgent.replan() 动态重规划剩余任务
     *
     * @return true 表示重规划成功，sortedTasks 已被更新
     */
    private boolean replanRemainingTasks(TaskList taskList, List<TaskItem> sortedTasks,
                                          int failedTaskIndex, TaskItem failedTask,
                                          Map<String, String> taskOutputs,
                                          AgentRuntimeContext context, SseEmitter emitter,
                                          String draftId) {
        try {
            // 1. 构建重规划 prompt
            String replanPrompt = buildReplanPrompt(taskList, failedTask, taskOutputs);

            // 2. 调用 PlannerAgent.replan()
            PlannerAgent plannerAgent = AiServices.builder(PlannerAgent.class)
                    .chatModel(chatLanguageModel)
                    .build();
            String replanResponse = plannerAgent.replan(replanPrompt);

            // 3. 解析重规划结果
            String cleanJson = extractJson(replanResponse);
            List<TaskItem> newRemainingTasks;
            try {
                newRemainingTasks = com.alibaba.fastjson.JSON.parseArray(cleanJson, TaskItem.class);
            } catch (Exception e) {
                log.error("[PlanAndExecute] 重规划 JSON 解析失败: {}", e.getMessage());
                return false;
            }

            if (newRemainingTasks == null || newRemainingTasks.isEmpty()) {
                log.warn("[PlanAndExecute] 重规划返回空任务列表");
                return false;
            }

            // 4. 保留已完成的任务，替换剩余任务
            List<TaskItem> completedTasks = new ArrayList<>();
            for (int i = 0; i < failedTaskIndex; i++) {
                completedTasks.add(sortedTasks.get(i));
            }

            List<TaskItem> mergedTasks = new ArrayList<>(completedTasks);
            mergedTasks.addAll(newRemainingTasks);

            // 5. 校验新的任务列表
            TaskList newTaskList = new TaskList();
            newTaskList.setTasks(mergedTasks);
            // 做一次拓扑排序校验（检测循环依赖）
            try {
                topologicalSort(newTaskList.getTasks());
            } catch (IllegalStateException e) {
                log.error("[PlanAndExecute] 重规划产生循环依赖: {}", e.getMessage());
                return false;
            }

            // 6. 替换 taskList 并持久化
            taskList.setTasks(mergedTasks);
            draftService.updateTaskList(draftId, taskList);

            log.info("[PlanAndExecute] 重规划成功: failedTaskId={}, newRemainingCount={}, totalTasks={}",
                    failedTask.getTaskId(), newRemainingTasks.size(), mergedTasks.size());
            return true;

        } catch (Exception e) {
            log.error("[PlanAndExecute] 重规划失败: taskId={}", failedTask.getTaskId(), e);
            return false;
        }
    }

    private String buildReplanPrompt(TaskList taskList, TaskItem failedTask,
                                     Map<String, String> taskOutputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户原始目标：").append(taskList.getUserGoal()).append("\n\n");

        // 已完成的任务
        sb.append("已完成的任务：\n");
        for (TaskItem task : taskList.getTasks()) {
            if ("COMPLETED".equals(task.getStatus())) {
                sb.append("- ").append(task.getTaskId()).append(": ").append(task.getDescription()).append("\n");
                sb.append("  结果: ").append(truncate(task.getResultSummary(), 200)).append("\n");
            }
        }

        // 当前失败的任务
        sb.append("\n验证失败的任务：\n");
        sb.append("- ").append(failedTask.getTaskId()).append(": ").append(failedTask.getDescription()).append("\n");
        sb.append("  失败原因: ").append(failedTask.getErrorMessage() != null
                ? failedTask.getErrorMessage() : "结果未通过质量验证").append("\n");
        sb.append("  预期产出: ").append(failedTask.getExpectedOutput() != null
                ? failedTask.getExpectedOutput() : "根据任务描述完成").append("\n");

        // 原始计划的剩余任务
        sb.append("\n原始计划中尚未执行的任务（供参考，可自由调整）：\n");
        for (TaskItem task : taskList.getTasks()) {
            if (!"COMPLETED".equals(task.getStatus()) && !task.getTaskId().equals(failedTask.getTaskId())) {
                sb.append("- ").append(task.getTaskId()).append(": ").append(task.getDescription()).append("\n");
            }
        }

        sb.append("\n请根据以上信息，重新规划剩余任务。");

        return sb.toString();
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

    private List<TaskItem> topologicalSort(List<TaskItem> tasks) {
        Map<String, TaskItem> taskMap = tasks.stream()
                .collect(Collectors.toMap(TaskItem::getTaskId, t -> t));

        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (TaskItem task : tasks) {
            adjacency.put(task.getTaskId(), new ArrayList<>());
            inDegree.putIfAbsent(task.getTaskId(), 0);
        }

        for (TaskItem task : tasks) {
            for (String dep : task.getDependsOn()) {
                adjacency.get(dep).add(task.getTaskId());
                inDegree.merge(task.getTaskId(), 1, Integer::sum);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<TaskItem> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String taskId = queue.poll();
            result.add(taskMap.get(taskId));
            for (String neighbor : adjacency.getOrDefault(taskId, List.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (result.size() != tasks.size()) {
            throw new IllegalStateException("TaskList 包含循环依赖");
        }

        return result;
    }

    // ============ 辅助方法 ============

    private boolean allDependenciesCompleted(TaskItem task, Map<String, String> taskOutputs) {
        for (String dep : task.getDependsOn()) {
            if (!taskOutputs.containsKey(dep)) return false;
        }
        return true;
    }

    private int findFirstConfirmationIndex(TaskList taskList) {
        for (int i = 0; i < taskList.getTasks().size(); i++) {
            if ("CONFIRMATION".equals(taskList.getTasks().get(i).getGate())) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> taskToPayload(TaskItem task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("description", task.getDescription());
        payload.put("status", task.getStatus());
        payload.put("gate", task.getGate());
        payload.put("riskLevel", task.getRiskLevel());
        payload.put("requiredTools", task.getRequiredTools());
        if (task.getResultSummary() != null) payload.put("resultSummary", task.getResultSummary());
        if (task.getErrorMessage() != null) payload.put("errorMessage", task.getErrorMessage());
        return payload;
    }

    private String buildPlanSummaryText(TaskList taskList) {
        StringBuilder sb = new StringBuilder();
        sb.append("我已为你规划了完成「").append(taskList.getUserGoal()).append("」的任务计划，共 ")
                .append(taskList.getTasks().size()).append(" 个步骤：\n\n");

        for (int i = 0; i < taskList.getTasks().size(); i++) {
            TaskItem task = taskList.getTasks().get(i);
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

    private String buildConfirmationText(TaskItem task, TaskList taskList) {
        return "即将执行：" + task.getDescription() + "\n请确认是否继续？";
    }

    private String buildFinalSummary(TaskList taskList) {
        long completed = taskList.getTasks().stream().filter(t -> "COMPLETED".equals(t.getStatus())).count();
        long failed = taskList.getTasks().stream().filter(t -> "FAILED".equals(t.getStatus())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("任务计划执行完成！共 ").append(taskList.getTasks().size())
                .append(" 个任务，成功 ").append(completed).append(" 个");
        if (failed > 0) {
            sb.append("，失败 ").append(failed).append(" 个");
        }
        sb.append("。\n");

        for (TaskItem task : taskList.getTasks()) {
            String icon = switch (task.getStatus()) {
                case "COMPLETED" -> "✓";
                case "FAILED" -> "✗";
                default -> "○";
            };
            sb.append(icon).append(" ").append(task.getTaskId()).append(": ").append(task.getDescription()).append("\n");
        }

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
