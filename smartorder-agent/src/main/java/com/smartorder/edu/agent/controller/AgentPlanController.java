package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.dto.PlanConfirmRequest;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.orchestrator.PlanAndExecuteEngine;
import com.smartorder.edu.agent.plan.PlanConfirmService;
import com.smartorder.edu.agent.plan.PlanDraftService;
import com.smartorder.edu.agent.plan.PlanDraftService.DraftValidationResult;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final AgentTraceEventMapper traceEventMapper;
    private final TraceEventDispatcher dispatcher;
    private final ExecutorService executor;

    public AgentPlanController(PlanConfirmService planConfirmService,
                               PlanDraftService planDraftService,
                               PlanAndExecuteEngine planAndExecuteEngine,
                               AgentTraceEventMapper traceEventMapper,
                               TraceEventDispatcher dispatcher,
                               @org.springframework.beans.factory.annotation.Qualifier("agentPlanExecutor") ExecutorService executor) {
        this.planConfirmService = planConfirmService;
        this.planDraftService = planDraftService;
        this.planAndExecuteEngine = planAndExecuteEngine;
        this.traceEventMapper = traceEventMapper;
        this.dispatcher = dispatcher;
        this.executor = executor;
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
        });

        emitter.onTimeout(() -> {
            log.warn("[PlanConfirm] SSE 超时: draftId={}", draftId);
            emitter.complete();
        });
        emitter.onError(ex -> log.error("[PlanConfirm] SSE 错误: draftId={}", draftId, ex));

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

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
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
