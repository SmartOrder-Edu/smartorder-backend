package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.dto.AgentChatRequest;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 对话控制器 — SSE 流式响应
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 对话", description = "AI Agent 流式对话接口")
public class AgentChatController {

    private final com.smartorder.edu.agent.service.MasterAgentService masterAgentService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "与 Agent 流式对话", description = "发送消息给主学习 Agent，通过 SSE 流式接收回复")
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        Long userId = getCurrentUserId();
        String conversationId = request.getConversationId() != null
                ? request.getConversationId() : UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(180_000L); // 3 分钟超时
        log.info("[AgentChat] 开始 SSE 会话: userId={}, cid={}", userId, conversationId);

        executor.execute(() -> {
            try {
                reactor.core.publisher.Flux<String> flux = masterAgentService.chat(
                        userId, request.getMessage(), conversationId);

                flux.doOnNext(token -> sendEvent(emitter, "token", token, conversationId))
                    .doOnComplete(() -> {
                        sendEvent(emitter, "done", "{\"conversationId\":\"" + conversationId + "\"}", conversationId);
                        emitter.complete();
                        log.info("[AgentChat] SSE 会话完成: cid={}", conversationId);
                    })
                    .doOnError(error -> {
                        log.error("[AgentChat] SSE 错误: {}", error.getMessage());
                        sendEvent(emitter, "error", "{\"message\":\"" + escapeJson(error.getMessage()) + "\"}", conversationId);
                        emitter.complete();
                    })
                    .subscribe();
            } catch (Exception e) {
                log.error("[AgentChat] 处理失败", e);
                sendEvent(emitter, "error", "{\"message\":\"" + escapeJson(e.getMessage()) + "\"}", conversationId);
                emitter.complete();
            }
        });

        // 注册超时和错误回调
        emitter.onTimeout(() -> log.warn("[AgentChat] SSE 超时: cid={}", conversationId));
        emitter.onError(ex -> log.error("[AgentChat] SSE 连接错误: cid={}, err={}", conversationId, ex.getMessage()));

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String event, String data, String cid) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(data));
        } catch (IOException e) {
            log.error("[AgentChat] SSE 发送失败: cid={}", cid);
            emitter.completeWithError(e);
        }
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
