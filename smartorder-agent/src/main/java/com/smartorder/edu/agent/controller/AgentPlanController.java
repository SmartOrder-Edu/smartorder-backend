package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.clarification.ClarificationService;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.dto.PlanConfirmRequest;
import com.smartorder.edu.agent.model.entity.AgentClarification;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.model.vo.ActiveRunVO;
import com.smartorder.edu.agent.model.vo.TimelineEventVO;
import com.smartorder.edu.agent.model.entity.AgentRunState;
import com.smartorder.edu.agent.orchestrator.PlanAndExecuteEngine;
import com.smartorder.edu.agent.plan.PlanConfirmService;
import com.smartorder.edu.agent.service.AgentRunStateService;
import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.plan.PlanDraftService.DraftValidationResult;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 规划控制器——规划确认、trace 查询等非对话接口。
 * <p>
 * 支持 EXECUTION_PLAN（Agent 执行规划）和 LEARNING_PLAN（学习计划草稿）两种草稿类型。
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 规划", description = "学习规划确认与执行接口")
public class AgentPlanController {

    private final PlanConfirmService planConfirmService;
    private final PlanDraftService planDraftService;
    private final PlanAndExecuteEngine planAndExecuteEngine;
    private final ClarificationService clarificationService;
    private final AgentTraceEventMapper traceEventMapper;
    private final TraceEventDispatcher dispatcher;
    private final ExecutorService executor;
    private final AgentRunStateService runStateService;
    private final AgentPlanDraftMapper draftMapper;

    public AgentPlanController(PlanConfirmService planConfirmService,
                               PlanDraftService planDraftService,
                               PlanAndExecuteEngine planAndExecuteEngine,
                               ClarificationService clarificationService,
                               AgentTraceEventMapper traceEventMapper,
                               TraceEventDispatcher dispatcher,
                               @org.springframework.beans.factory.annotation.Qualifier("agentPlanExecutor") ExecutorService executor,
                               AgentRunStateService runStateService,
                               AgentPlanDraftMapper draftMapper) {
        this.planConfirmService = planConfirmService;
        this.planDraftService = planDraftService;
        this.planAndExecuteEngine = planAndExecuteEngine;
        this.clarificationService = clarificationService;
        this.traceEventMapper = traceEventMapper;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.runStateService = runStateService;
        this.draftMapper = draftMapper;
    }

    @PostMapping(value = "/plan/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "确认学习规划并执行",
               description = "用户确认规划草稿后，后端根据草稿类型走不同执行路径。通过 SSE 推送执行进度。")
    public SseEmitter confirmPlan(@RequestBody PlanConfirmRequest request) {
        Long userId = getCurrentUserId();
        String conversationId = request.getConversationId() != null
                ? request.getConversationId() : UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String draftId = request.getDraftId();

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟，执行步骤较多
        log.info("[PlanConfirm] 开始执行规划: userId={}, draftId={}", userId, draftId);

        executor.execute(() -> {
            try {
            // 先按草稿类型路由（保持 EXECUTION_PLAN 链路不变）
            AgentPlanDraft draft = planDraftService.getByDraftId(draftId);
            if (draft == null) {
                log.error("[PlanConfirm] 草稿不存在: {}", draftId);
                emitErrorAndClose(emitter, conversationId, userId, traceId,
                        "DRAFT_NOT_FOUND", "学习计划草稿不存在: " + draftId);
                return;
            }

            String planType = draft.getPlanType();
            if ("EXECUTION_PLAN".equals(planType)) {
                // Agent 执行规划：从确认门恢复执行（保持现有逻辑）
                planAndExecuteEngine.executeExecutionPlan(
                        draftId, userId, conversationId, emitter, traceId);
                return;
            }

            if (!"LEARNING_PLAN".equals(planType)) {
                log.error("[PlanConfirm] 未知的 planType: {}", planType);
                emitErrorAndClose(emitter, conversationId, userId, traceId,
                        "UNKNOWN_PLAN_TYPE", "未知的草稿类型: " + planType);
                return;
            }

            // ============ LEARNING_PLAN 校验前置 ============
            DraftValidationResult result = planDraftService.validateForConfirm(draftId, userId);
            switch (result.status()) {
                case OK:
                    planConfirmService.createLearningSpace(
                            draftId, userId, conversationId, emitter, traceId);
                    return;
                case ALREADY_CONFIRMED:
                    log.info("[PlanConfirm] 草稿已确认（Controller 层快速返回）: draftId={}, spaceId={}",
                            draftId, result.createdSpaceId());
                    AgentRuntimeContext ctx = buildContext(traceId, conversationId, userId);
                    dispatcher.emit(emitter, ctx, AgentEventType.STATUS, Map.of(
                            "type", "space_already_created",
                            "spaceId", result.createdSpaceId(),
                            "message", "学习空间已创建过，直接进入即可。"
                    ));
                    dispatcher.complete(emitter, ctx);
                    return;
                case NOT_FOUND:
                    emitErrorAndClose(emitter, conversationId, userId, traceId,
                            "DRAFT_NOT_FOUND", result.message());
                    return;
                case FORBIDDEN:
                    emitErrorAndClose(emitter, conversationId, userId, traceId,
                            "FORBIDDEN", result.message());
                    return;
                case EXPIRED:
                    emitErrorAndClose(emitter, conversationId, userId, traceId,
                            "DRAFT_EXPIRED", result.message());
                    return;
                case WRONG_TYPE:
                    emitErrorAndClose(emitter, conversationId, userId, traceId,
                            "WRONG_PLAN_TYPE", result.message());
                    return;
                case WRONG_STATUS:
                default:
                    emitErrorAndClose(emitter, conversationId, userId, traceId,
                            "DRAFT_WRONG_STATUS", result.message());
                    return;
            }
            } catch (Exception e) {
                log.error("[PlanConfirm] 执行规划异常: userId={}, draftId={}", userId, draftId, e);
                emitErrorAndClose(emitter, conversationId, userId, traceId,
                        "PLAN_CONFIRM_FAILED", e.getMessage() != null ? e.getMessage() : "规划执行失败");
            }
        });

        emitter.onTimeout(() -> {
            log.warn("[PlanConfirm] SSE 超时: draftId={}", draftId);
            emitter.complete();
        });
        emitter.onError(ex -> log.error("[PlanConfirm] SSE 错误: draftId={}", draftId, ex));

        return emitter;
    }

    /**
     * 查询会话的澄清记录（历史回放用）
     */
    @GetMapping("/clarifications/{conversationId}")
    @Operation(summary = "查询会话澄清记录",
               description = "获取指定会话的全部澄清记录，用于历史回放时重建问答对。")
    public List<com.smartorder.edu.agent.model.entity.AgentClarification> getClarifications(
            @PathVariable String conversationId) {
        Long userId = getCurrentUserId();
        log.info("[Clarification] 查询会话澄清记录: userId={}, convId={}", userId, conversationId);
        return clarificationService.getClarificationsForConversation(conversationId);
    }

    /**
     * 专用澄清回答 API——绑定 draftId + clarificationId，后端无需推断意图
     */
    @PostMapping(value = "/plans/{draftId}/clarifications/{clarificationId}/answer",
                  produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "提交澄清回答",
               description = "提交结构化回答到指定澄清槽位。若全部澄清已回答则恢复执行，否则推送下一个问题。通过 SSE 推送进度。")
    public SseEmitter answerClarification(
            @PathVariable String draftId,
            @PathVariable String clarificationId,
            @RequestBody com.smartorder.edu.agent.model.dto.ClarificationAnswerRequest request) {
        Long userId = getCurrentUserId();
        String traceId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300_000L);
        log.info("[ClarificationAnswer] 提交澄清回答: userId={}, draftId={}, clarId={}", userId, draftId, clarificationId);

        executor.execute(() -> {
            try {
                planAndExecuteEngine.resumeAfterClarification(
                        draftId, clarificationId, request.getAnswer(), request.getDisplayAnswer(),
                        userId, request.getConversationId(), emitter, traceId);
            } catch (Exception e) {
                log.error("[ClarificationAnswer] 恢复执行异常: userId={}, draftId={}, clarId={}",
                        userId, draftId, clarificationId, e);
                emitErrorAndClose(emitter, request.getConversationId(), userId, traceId,
                        "CLARIFICATION_RESUME_FAILED",
                        e.getMessage() != null ? e.getMessage() : "澄清恢复执行失败");
            }
        });

        emitter.onTimeout(() -> {
            log.warn("[ClarificationAnswer] SSE 超时: draftId={}", draftId);
            emitter.complete();
        });
        emitter.onError(ex -> log.error("[ClarificationAnswer] SSE 错误: draftId={}", draftId, ex));

        return emitter;
    }

    /**
     * 专用确认门 API——绑定 draftId，用于 CONFIRMATION gate 的用户确认
     */
    @PostMapping(value = "/plans/{draftId}/confirm-gate",
                  produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "确认确认门",
               description = "用户确认 PAUSED_CONFIRMATION 门后恢复执行。通过 SSE 推送进度。")
    public SseEmitter confirmGate(
            @PathVariable String draftId,
            @RequestBody(required = false) com.smartorder.edu.agent.model.dto.GateConfirmRequest request) {
        Long userId = getCurrentUserId();
        String conversationId = request != null ? request.getConversationId() : null;
        String traceId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300_000L);
        log.info("[ConfirmGate] 确认确认门: userId={}, draftId={}", userId, draftId);

        executor.execute(() -> {
            try {
                planAndExecuteEngine.resumeAfterConfirmation(
                        draftId, userId, conversationId, emitter, traceId);
            } catch (Exception e) {
                log.error("[ConfirmGate] 恢复执行异常: userId={}, draftId={}", userId, draftId, e);
                emitErrorAndClose(emitter, conversationId, userId, traceId,
                        "CONFIRMATION_RESUME_FAILED",
                        e.getMessage() != null ? e.getMessage() : "确认门恢复执行失败");
            }
        });

        emitter.onTimeout(() -> {
            log.warn("[ConfirmGate] SSE 超时: draftId={}", draftId);
            emitter.complete();
        });
        emitter.onError(ex -> log.error("[ConfirmGate] SSE 错误: draftId={}", draftId, ex));

        return emitter;
    }

    /**
     * 按 traceId 查询追踪事件，支持前端重放 Agent 执行链路
     */
    @GetMapping("/traces/{traceId}")
    @Operation(summary = "查询追踪事件列表",
               description = "根据 traceId 查询某次 Agent 执行的完整追踪事件，按 seq 正序返回，支持前端回放执行链路。")
    public List<AgentTraceEvent> getTraceEvents(@PathVariable String traceId) {
        log.info("[TraceQuery] 查询追踪事件: traceId={}", traceId);
        return traceEventMapper.selectByTraceId(traceId);
    }

    /**
     * 获取会话的活跃计划运行态——前端加载历史会话时调用，恢复 agentStore 中的计划状态
     */
    @GetMapping("/conversations/{conversationId}/active-run")
    @Operation(summary = "获取会话活跃计划运行态",
               description = "查询指定会话是否有未完成的 EXECUTION_PLAN，如有则返回计划状态、任务列表、暂停信息，"
                       + "用于前端恢复 agentStore 中的 currentTaskList / planPauseInfo / currentDraftId。")
    public Result<ActiveRunVO> getActiveRun(@PathVariable String conversationId) {
        Long userId = getCurrentUserId();
        AgentPlanDraft draft = planDraftService.getActiveExecutionPlanDraft(conversationId);
        if (draft == null || !"EXECUTION_PLAN".equals(draft.getPlanType())) {
            return Result.success(null);
        }

        ExecutionPlan plan;
        try {
            plan = com.alibaba.fastjson.JSON.parseObject(draft.getExecutionPlanJson(), ExecutionPlan.class);
        } catch (Exception e) {
            log.warn("[ActiveRun] 反序列化 ExecutionPlan 失败: draftId={}", draft.getDraftId(), e);
            return Result.success(null);
        }

        if (plan == null) return Result.success(null);

        // 优先使用 runState 的状态（Phase 3 权威数据源）
        String planStatus = plan.getStatus();
        AgentRunState runState = runStateService.getByDraftId(draft.getDraftId());
        if (runState != null) {
            planStatus = runState.getStatus();
        }

        // 对于终态计划，仍尝试查找关联的已创建学习空间信息
        if ("COMPLETED".equals(planStatus) || "FAILED".equals(planStatus) || "CANCELLED".equals(planStatus)) {
            ActiveRunVO.SpaceCreatedVO spaceCreated = findSpaceCreatedForConversation(conversationId);
            if (spaceCreated != null) {
                // 构造最小 ActiveRunVO，仅携带 spaceCreated 供前端恢复 banner
                ActiveRunVO vo = ActiveRunVO.builder()
                        .draftId(draft.getDraftId())
                        .planType("EXECUTION_PLAN")
                        .status(planStatus)
                        .spaceCreated(spaceCreated)
                        .build();
                return Result.success(vo);
            }
            return Result.success(null);
        }

        // 构建 TaskListVO
        ActiveRunVO.TaskListVO taskListVO = ActiveRunVO.TaskListVO.builder()
                .userGoal(plan.getUserGoal())
                .planId(plan.getPlanId())
                .status(planStatus)
                .currentTaskIndex(plan.getCurrentStepIndex())
                .taskOutputs(plan.getStepOutputs())
                .createdAt(plan.getCreatedAt())
                .clarificationQuestion(plan.getClarificationQuestion())
                .confirmationDraftId(plan.getConfirmationDraftId())
                .build();

        if (plan.getSteps() != null) {
            taskListVO.setTasks(plan.getSteps().stream()
                    .map(this::toTaskItemVO)
                    .toList());
        }

        // 构建 PauseInfoVO（暂停态时）
        ActiveRunVO.PauseInfoVO pauseInfoVO = null;
        String activeClarificationId = null;
        if ("PAUSED_CLARIFICATION".equals(planStatus)) {
            // 查询当前 PENDING 的澄清记录
            List<AgentClarification> pendingClarifications =
                    clarificationService.getClarificationsForDraft(draft.getDraftId()).stream()
                            .filter(c -> "PENDING".equals(c.getStatus()))
                            .sorted((a, b) -> Integer.compare(a.getSlotPriority(), b.getSlotPriority()))
                            .toList();

            AgentClarification nextPending = pendingClarifications.isEmpty() ? null : pendingClarifications.get(0);

            int pausedIndex = plan.getCurrentStepIndex();
            String pausedTaskId = pausedIndex < plan.getSteps().size()
                    ? plan.getSteps().get(pausedIndex).getStepId() : null;

            ActiveRunVO.ClarificationInfoVO clarInfo = null;
            if (nextPending != null) {
                activeClarificationId = nextPending.getClarificationId();
                clarInfo = ActiveRunVO.ClarificationInfoVO.builder()
                        .clarificationId(nextPending.getClarificationId())
                        .slotKey(nextPending.getSlotKey())
                        .question(nextPending.getQuestion())
                        .answerType(nextPending.getAnswerType())
                        .slotPriority(nextPending.getSlotPriority())
                        .build();
                // 解析 options
                if (nextPending.getOptionsJson() != null) {
                    try {
                        List<Map> opts = com.alibaba.fastjson.JSON.parseArray(nextPending.getOptionsJson(), Map.class);
                        clarInfo.setOptions(opts.stream().map(m -> Map.of(
                                "label", String.valueOf(m.getOrDefault("label", "")),
                                "value", String.valueOf(m.getOrDefault("value", ""))
                        )).toList());
                    } catch (Exception ignored) {}
                }
            }

            pauseInfoVO = ActiveRunVO.PauseInfoVO.builder()
                    .reason("CLARIFICATION")
                    .taskId(pausedTaskId)
                    .question(plan.getClarificationQuestion())
                    .clarification(clarInfo)
                    .build();
        } else if ("PAUSED_CONFIRMATION".equals(planStatus)) {
            int pausedIndex = plan.getCurrentStepIndex();
            String pausedTaskId = pausedIndex < plan.getSteps().size()
                    ? plan.getSteps().get(pausedIndex).getStepId() : null;
            pauseInfoVO = ActiveRunVO.PauseInfoVO.builder()
                    .reason("CONFIRMATION")
                    .taskId(pausedTaskId)
                    .question(null)
                    .build();
        }

        ActiveRunVO.SpaceCreatedVO spaceCreated = findSpaceCreatedForConversation(conversationId);

        ActiveRunVO vo = ActiveRunVO.builder()
                .draftId(draft.getDraftId())
                .planType("EXECUTION_PLAN")
                .status(planStatus)
                .taskList(taskListVO)
                .pauseInfo(pauseInfoVO)
                .activeClarificationId(activeClarificationId)
                .spaceCreated(spaceCreated)
                .build();

        return Result.success(vo);
    }

    /**
     * 获取 Run 的标准化 Timeline——用于前端历史回放，不再逐条拉 trace
     */
    @GetMapping("/runs/{runId}/timeline")
    @Operation(summary = "获取 Run 时间线",
               description = "返回标准化后的 plan snapshot + gate events + task events + tool calls，前端直接渲染时间线组件。")
    public Result<List<TimelineEventVO>> getRunTimeline(
            @PathVariable String runId) {
        Long userId = getCurrentUserId();
        AgentRunState runState = runStateService.getByRunId(runId);
        if (runState == null || runState.getTraceId() == null) {
            return Result.success(List.of());
        }

        List<AgentTraceEvent> events = traceEventMapper.selectByTraceId(runState.getTraceId());
        List<TimelineEventVO> timeline = events.stream()
                .filter(e -> isTimelineRelevant(e.getEventType()))
                .map(this::toTimelineEvent)
                .toList();
        return Result.success(timeline);
    }

    private boolean isTimelineRelevant(String eventType) {
        return switch (eventType) {
            case "agent.plan.created", "agent.plan.task.started", "agent.tool.started",
                 "agent.plan.task.completed", "agent.plan.task.failed",
                 "agent.plan.paused", "agent.plan.resumed", "agent.trace.completed" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private TimelineEventVO toTimelineEvent(AgentTraceEvent e) {
        String mappedType = mapTimelineType(e.getEventType());
        String taskId = null;
        String description = null;
        String status = null;
        Map<String, Object> details = new LinkedHashMap<>();

        if (e.getPayload() != null) {
            try {
                Map<String, Object> p = com.alibaba.fastjson.JSON.parseObject(
                        e.getPayload(), Map.class);
                taskId = (String) p.getOrDefault("taskId", p.get("stepId"));
                description = (String) p.getOrDefault("description",
                        p.getOrDefault("question", p.get("summary")));
                status = (String) p.getOrDefault("status",
                        p.getOrDefault("reason", null));
                // 提取 toolName 和 resultSummary
                if (p.containsKey("toolName")) details.put("toolName", p.get("toolName"));
                if (p.containsKey("resultSummary")) details.put("resultSummary", p.get("resultSummary"));
                if (p.containsKey("errorMessage")) details.put("errorMessage", p.get("errorMessage"));
                if (p.containsKey("question")) details.put("question", p.get("question"));
                if (p.containsKey("clarification")) details.put("clarification", p.get("clarification"));
            } catch (Exception ignored) {}
        }

        return TimelineEventVO.builder()
                .eventType(mappedType)
                .taskId(taskId)
                .description(description)
                .status(status)
                .details(details)
                .timestamp(e.getCreateTime() != null
                        ? e.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : System.currentTimeMillis())
                .seq(e.getSequenceNum())
                .build();
    }

    private String mapTimelineType(String eventType) {
        return switch (eventType) {
            case "agent.plan.created" -> "PLAN_CREATED";
            case "agent.plan.task.started" -> "TASK_STARTED";
            case "agent.tool.started" -> "TOOL_CALL";
            case "agent.plan.task.completed" -> "TASK_COMPLETED";
            case "agent.plan.task.failed" -> "TASK_COMPLETED";
            case "agent.plan.paused" -> "GATE_PAUSED";
            case "agent.plan.resumed" -> "GATE_ANSWERED";
            case "agent.trace.completed" -> "RUN_COMPLETED";
            default -> eventType;
        };
    }

    private ActiveRunVO.TaskItemVO toTaskItemVO(ExecutionStep step) {
        return ActiveRunVO.TaskItemVO.builder()
                .taskId(step.getStepId())
                .description(step.getDescription())
                .requiredTools(step.getRequiredTools())
                .dependsOn(step.getDependsOn())
                .gate(step.getGate())
                .riskLevel(step.getRiskLevel())
                .expectedOutput(step.getExpectedOutput())
                .status(step.getStatus())
                .resultSummary(step.getResultSummary())
                .errorMessage(step.getErrorMessage())
                .retryCount(step.getRetryCount())
                .build();
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }

    /**
     * 查找会话中已确认的 LEARNING_PLAN 草稿对应的学习空间信息。
     * 用于前端恢复 PlanChecklist 的"学习空间已创建"banner。
     */
    private ActiveRunVO.SpaceCreatedVO findSpaceCreatedForConversation(String conversationId) {
        AgentPlanDraft learningPlanDraft = draftMapper.selectConfirmedLearningPlanDraft(conversationId);
        if (learningPlanDraft == null || learningPlanDraft.getCreatedSpaceId() == null) {
            return null;
        }
        String spaceName = "";
        try {
            if (learningPlanDraft.getPlanJson() != null) {
                var planJson = com.alibaba.fastjson.JSON.parseObject(learningPlanDraft.getPlanJson());
                spaceName = planJson.getString("spaceName");
                if (spaceName == null) spaceName = "";
            }
        } catch (Exception e) {
            log.debug("[ActiveRun] 解析 LEARNING_PLAN spaceName 失败（非关键）: {}", e.getMessage());
        }
        return ActiveRunVO.SpaceCreatedVO.builder()
                .spaceId(learningPlanDraft.getCreatedSpaceId())
                .spaceName(spaceName)
                .build();
    }

    private AgentRuntimeContext buildContext(String traceId, String conversationId, Long userId) {
        return AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .sequence(new AtomicInteger(0))
                .build();
    }

    private void emitErrorAndClose(SseEmitter emitter, String conversationId, Long userId,
                                    String traceId, String errorCode, String message) {
        log.warn("[PlanConfirm] {} : {}", errorCode, message);
        AgentRuntimeContext ctx = buildContext(traceId, conversationId, userId);
        dispatcher.error(emitter, ctx, new IllegalStateException(errorCode + ": " + message));
    }
}
