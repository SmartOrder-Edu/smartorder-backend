package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.dto.PlanConfirmRequest;
import com.smartorder.edu.agent.plan.PlanConfirmService;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 规划控制器——规划确认等非对话接口
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 规划", description = "学习规划确认与执行接口")
public class AgentPlanController {

    private final PlanConfirmService planConfirmService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/plan/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "确认学习规划并执行",
               description = "用户确认规划草稿后，后端创建学习空间、任务、知识图谱和资源。通过 SSE 推送执行进度。")
    public SseEmitter confirmPlan(@RequestBody PlanConfirmRequest request) {
        Long userId = getCurrentUserId();
        String conversationId = request.getConversationId() != null
                ? request.getConversationId() : UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟，执行步骤较多
        log.info("[PlanConfirm] 开始执行规划: userId={}, draftId={}", userId, request.getDraftId());

        executor.execute(() -> {
            planConfirmService.confirmAndExecute(
                    request.getDraftId(), userId, conversationId, emitter, traceId);
        });

        emitter.onTimeout(() -> log.warn("[PlanConfirm] SSE 超时: draftId={}", request.getDraftId()));
        emitter.onError(ex -> log.error("[PlanConfirm] SSE 错误: draftId={}", request.getDraftId(), ex));

        return emitter;
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
