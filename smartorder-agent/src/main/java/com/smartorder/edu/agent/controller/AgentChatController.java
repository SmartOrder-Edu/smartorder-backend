package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.orchestrator.AgentOrchestrator;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 对话控制器 — 结构化 SSE 流式响应
 *
 * 重构后：Controller 只接收请求，委托 AgentOrchestrator 编排执行。
 * 不再手动解析 JSON action、不再使用 [STATUS]/[PLAN_RESULT] 文本标记。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 对话", description = "AI Agent 流式对话接口")
public class AgentChatController {

    private final AgentOrchestrator agentOrchestrator;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "与 Agent 流式对话（结构化 SSE）",
               description = "发送消息给学习 Agent，通过结构化 SSE 事件流接收回复。" +
                       "事件类型包括 agent.trace.started、agent.route.completed、agent.think.delta、" +
                       "agent.tool.started/completed、agent.text.delta、agent.trace.completed、done")
    public SseEmitter chatStream(@RequestBody AgentChatRequest request) {
        // 从 JWT 获取 userId
        Long userId = getCurrentUserId();
        request.setUserId(userId);

        SseEmitter emitter = new SseEmitter(180_000L);
        log.info("[AgentChat] SSE 会话开始: userId={}, cid={}, message={}",
                userId, request.getConversationId(), request.getMessage());

        executor.execute(() -> {
            agentOrchestrator.handle(request, emitter);
        });

        emitter.onTimeout(() -> log.warn("[AgentChat] SSE 超时: cid={}", request.getConversationId()));
        emitter.onError(ex -> log.error("[AgentChat] SSE 连接错误: cid={}", request.getConversationId(), ex));

        return emitter;
    }

    /**
     * 旧接口保留兼容，映射到新路径
     */
    @Deprecated
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "[已废弃] 旧版 Agent 对话", description = "请使用 /agent/chat/stream")
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        return chatStream(request);
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
