package com.smartorder.edu.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.TaskItem;
import com.smartorder.edu.agent.model.dto.TaskList;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
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
 * Plan-then-ReAct 混合引擎——替代 PlanExecuteEngine + MultiAgentCoordinator
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
public class PlanThenReActEngine {

    private final ChatModel chatLanguageModel;
    private final ToolRegistry toolRegistry;
    private final LangChain4jToolAdapter toolAdapter;
    private final PlanSchemaValidator schemaValidator;
    private final PlanDraftService draftService;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;
    private final SelfManagedReActEngine reActEngine;

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
            log.error("[PlanThenReAct] PlannerAgent 调用失败", e);
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
            log.error("[PlanThenReAct] TaskList 校验失败: {}", e.getMessage());
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
            log.error("[PlanThenReAct] 草稿不存在: {}", draftId);
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
                .mode(AgentRunMode.PLAN_THEN_REACT)
                .sequence(draft.getResumeTaskIndex() != null
                        ? new java.util.concurrent.atomic.AtomicInteger(draft.getResumeTaskIndex() * 100)
                        : new java.util.concurrent.atomic.AtomicInteger(0))
                .build();

        // 3. 拓扑排序
        List<TaskItem> sortedTasks = topologicalSort(taskList.getTasks());

        // 4. 执行任务
        Map<String, String> taskOutputs = new LinkedHashMap<>(taskList.getTaskOutputs());
        taskList.setStatus("EXECUTING");

        for (int i = taskList.getCurrentTaskIndex(); i < sortedTasks.size(); i++) {
            TaskItem task = sortedTasks.get(i);

            // 跳过已完成的任务
            if ("COMPLETED".equals(task.getStatus())) {
                continue;
            }

            // 检查依赖
            if (!allDependenciesCompleted(task, taskOutputs)) {
                task.setStatus("FAILED");
                task.setErrorMessage("依赖任务未完成: " + task.getDependsOn());
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED, taskToPayload(task));
                continue;
            }

            // 发射任务开始
            task.setStatus("RUNNING");
            dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_STARTED, taskToPayload(task));

            // 根据门类型分发
            switch (task.getGate()) {
                case "CLARIFICATION" -> {
                    handleClarificationGate(taskList, i, context, emitter, null);
                    taskList.setCurrentTaskIndex(i);
                    draftService.updateTaskList(draftId, taskList);
                    return; // 暂停
                }
                case "CONFIRMATION" -> {
                    // 暂停等待确认
                    taskList.setCurrentTaskIndex(i);
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
                    memoryService.saveAssistantMessage(conversationId, userId,
                            confirmText, null, traceId, null);
                    return; // 暂停
                }
                default -> {
                    // NORMAL：执行任务
                    boolean isConfirmationTask = "WRITE_CONFIRMED".equals(task.getRiskLevel());
                    try {
                        String result = executeTaskViaReAct(task, taskOutputs, context, emitter,
                                isConfirmationTask);

                        task.setStatus("COMPLETED");
                        task.setResultSummary(truncate(result, 500));
                        taskOutputs.put(task.getTaskId(), truncate(result, 2000));
                        taskList.setTaskOutputs(taskOutputs);

                        dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_COMPLETED,
                                taskToPayload(task));

                    } catch (Exception e) {
                        log.error("[PlanThenReAct] 任务执行失败: taskId={}", task.getTaskId(), e);
                        task.setStatus("FAILED");
                        task.setErrorMessage(e.getMessage());
                        dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED,
                                taskToPayload(task));
                    }
                }
            }

            // 更新 DB
            taskList.setCurrentTaskIndex(i + 1);
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
            log.warn("[PlanThenReAct] 未找到活跃的 TaskList 草稿: convId={}", context.getConversationId());
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
            log.warn("[PlanThenReAct] 获取学生画像失败，继续生成规划: {}", e.getMessage());
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
     * 执行任务直到遇到确认门（执行 CONFIRMATION 之前的 NORMAL 任务）
     */
    private void executeTasksUntilGate(String draftId, TaskList taskList, int gateIndex,
                                        AgentRuntimeContext context, SseEmitter emitter,
                                        AgentChatRequest request) {
        Map<String, String> taskOutputs = new LinkedHashMap<>(taskList.getTaskOutputs());

        for (int i = 0; i < gateIndex; i++) {
            TaskItem task = taskList.getTasks().get(i);

            if ("COMPLETED".equals(task.getStatus())) continue;

            task.setStatus("RUNNING");
            dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_STARTED, taskToPayload(task));

            try {
                String result = executeTaskViaReAct(task, taskOutputs, context, emitter, false);

                task.setStatus("COMPLETED");
                task.setResultSummary(truncate(result, 500));
                taskOutputs.put(task.getTaskId(), truncate(result, 2000));
                taskList.setTaskOutputs(taskOutputs);

                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_COMPLETED, taskToPayload(task));
            } catch (Exception e) {
                log.error("[PlanThenReAct] 任务执行失败: taskId={}", task.getTaskId(), e);
                task.setStatus("FAILED");
                task.setErrorMessage(e.getMessage());
                dispatcher.emit(emitter, context, AgentEventType.PLAN_TASK_FAILED, taskToPayload(task));
            }

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

        // 4. 通过 ReAct 引擎执行（带任务上下文）
        reActEngine.executeWithTaskContext(taskRequest, taskRoute, context, emitter,
                taskSystemMessage, previousOutputs, confirmationGranted);

        // 5. 提取最后一条 assistant 消息作为结果
        return extractLastAssistantMessage(context.getConversationId(), context.getUserId());
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

    private String extractLastAssistantMessage(String conversationId, Long userId) {
        // 从 DB 获取最后一条 assistant 消息
        try {
            // ConversationMemoryService 没有直接获取最后消息的方法，这里用简化实现
            return "任务执行完成"; // 默认摘要，实际内容已通过 SSE 推送
        } catch (Exception e) {
            log.warn("[PlanThenReAct] 提取最后消息失败: {}", e.getMessage());
            return "任务执行完成";
        }
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
