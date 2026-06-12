package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.dto.PlanConfirmRequest;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.orchestrator.PlanAndExecuteEngine;
import com.smartorder.edu.agent.plan.PlanConfirmService;
import com.smartorder.edu.agent.plan.PlanDraftService;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 规划控制器——规划确认、trace 查询等非对话接口
 * <p>
 * 支持 LEGACY（旧 PlanConfirmService）和 TASK_LIST（新 PlanAndExecuteEngine）两种草稿类型。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 规划", description = "学习规划确认与执行接口")
public class AgentPlanController {

    private final PlanConfirmService planConfirmService;
    private final PlanDraftService planDraftService;
    private final PlanAndExecuteEngine planAndExecuteEngine;
    private final AgentTraceEventMapper traceEventMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/plan/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "确认学习规划并执行",
               description = "用户确认规划草稿后，后端根据草稿类型走不同执行路径。通过 SSE 推送执行进度。")
    public SseEmitter confirmPlan(@RequestBody PlanConfirmRequest request) {
        Long userId = getCurrentUserId();
        String conversationId = request.getConversationId() != null
                ? request.getConversationId() : UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟，执行步骤较多
        log.info("[PlanConfirm] 开始执行规划: userId={}, draftId={}", userId, request.getDraftId());

        executor.execute(() -> {
            // 根据 planType 分发到不同引擎
            AgentPlanDraft draft = planDraftService.getByDraftId(request.getDraftId());
            if (draft != null && "TASK_LIST".equals(draft.getPlanType())) {
                // 新架构：PlanAndExecuteEngine 从确认门恢复执行
                planAndExecuteEngine.executeTaskList(
                        request.getDraftId(), userId, conversationId, emitter, traceId);
            } else {
                // 旧架构：PlanConfirmService 创建学习空间
                planConfirmService.confirmAndExecute(
                        request.getDraftId(), userId, conversationId, emitter, traceId);
            }
        });

        emitter.onTimeout(() -> log.warn("[PlanConfirm] SSE 超时: draftId={}", request.getDraftId()));
        emitter.onError(ex -> log.error("[PlanConfirm] SSE 错误: draftId={}", request.getDraftId(), ex));

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
}
