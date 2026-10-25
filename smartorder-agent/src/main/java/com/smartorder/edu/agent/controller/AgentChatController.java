package com.smartorder.edu.agent.controller;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.generation.AgentGenerationException;
import com.smartorder.edu.agent.generation.AgentGenerationService;
import com.smartorder.edu.agent.generation.AgentGenerationSubscriptionPlan;
import com.smartorder.edu.agent.generation.AgentGenerationVO;
import com.smartorder.edu.agent.generation.SseEmitterEventSink;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent 对话控制器 — 结构化 SSE 流式响应（兼容入口）
 *
 * <p>新链路请使用 {@code POST /agent/generations}（同步返回 conversationId / messageId）
 * + {@code GET /agent/generations/{id}/events}（SSE 输出与断线重连）。</p>
 *
 * <p>这里保留旧的 POST 入口，等价于“创建生成 + 从 0 订阅”，
 * 让尚未迁移的页面（资源 AI 面板、uni-app）继续可用，并同样具备服务端持续生成的能力。</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 对话", description = "AI Agent 流式对话接口")
public class AgentChatController {

    private final AgentGenerationService generationService;

    public AgentChatController(AgentGenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "与 Agent 流式对话（结构化 SSE）",
               description = "发送消息给学习 Agent，通过结构化 SSE 事件流接收回复。" +
                       "事件类型包括 agent.generation.started、agent.trace.started、agent.route.completed、" +
                       "agent.think.delta、agent.tool.started/completed、agent.text.delta、" +
                       "agent.trace.completed、done")
    public SseEmitter chatStream(@RequestBody AgentChatRequest request) {
        Long userId = getCurrentUserId();
        request.setUserId(userId);

        // 连接超时交给心跳与客户端重连处理：这里不设固定超时，避免长回答被 180s 截断
        SseEmitter emitter = new SseEmitter(0L);
        log.info("[AgentChat] SSE 会话开始: userId={}, cid={}, clientRequestId={}, message={}",
                userId, request.getConversationId(), request.getClientRequestId(), request.getMessage());

        AgentGenerationVO generation;
        try {
            generation = generationService.create(request, userId, request.getClientRequestId());
        } catch (Exception e) {
            // 旧入口不能返回 JSON：用 SSE 的 error + done 收敛，避免前端一直转圈
            log.warn("[AgentChat] 创建生成失败: userId={}, cid={}, reason={}",
                    userId, request.getConversationId(), e.getMessage());
            return sseError(e.getMessage() != null ? e.getMessage() : "会话不可用，请刷新后重试");
        }

        AgentGenerationSubscriptionPlan plan;
        try {
            // 同步校验（通常只是内存检查），失败仍可以走 SSE error + done 收敛
            plan = generationService.validateSubscription(generation.getGenerationId(), userId, 0L,
                    new SseEmitterEventSink(emitter));
        } catch (AgentGenerationException e) {
            log.warn("[AgentChat] 订阅校验失败: generationId={}, reason={}",
                    generation.getGenerationId(), e.getMessage());
            return sseError(e.getMessage() != null ? e.getMessage() : "订阅失败，请稍后重试");
        }

        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> log.error("[AgentChat] SSE 连接错误: cid={}, generationId={}",
                request.getConversationId(), generation.getGenerationId(), ex));

        try {
            // 回放与实时接入异步执行，避免 servlet 线程先写满整段 backlog
            generationService.attachAsync(plan);
        } catch (RejectedExecutionException e) {
            log.error("[AgentChat] 订阅线程池已满: generationId={}", generation.getGenerationId());
            return sseError("系统繁忙，订阅暂不可用，请稍后重试");
        }
        return emitter;
    }

    /**
     * 兼容入口的失败收敛：用一条 error + 一条 done 结束事件流。
     * 这两条 event 在控制器返回前发送，会由 SseEmitter 作为 early send 缓冲，不会丢。
     */
    private SseEmitter sseError(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "agent.error");
            event.put("timestamp", System.currentTimeMillis());
            event.put("payload", Map.of("message", message));
            emitter.send(SseEmitter.event().name("agent.error").data(JSON.toJSONString(event)));

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("error", message);
            done.put("status", "FAILED");
            emitter.send(SseEmitter.event().name("done").data(JSON.toJSONString(done)));
        } catch (Exception sendError) {
            log.debug("[AgentChat] 错误事件发送失败: {}", sendError.getMessage());
        }
        emitter.complete();
        return emitter;
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
