package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.orchestrator.AgentOrchestrator;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;

/**
 * 旧版 Agent 对话控制器 — 单次 POST 直接返回结构化 SSE。
 *
 * 主聊天已重构为 AgentTaskController 的任务创建、快照和只读事件订阅。
 * 站内页面已迁移；此入口暂为旧客户端保留兼容行为。
 *
 * @deprecated 整个控制器只剩 {@code POST /agent/chat/stream} 一个兼容入口，站内页面已全部迁移。
 * 新调用方请使用 {@link AgentTaskController}：{@code POST /agent/tasks} 创建任务、
 * {@code GET /agent/tasks/{taskId}/snapshot} 对齐快照、
 * {@code GET /agent/tasks/{taskId}/events} 按 Last-Event-ID 续传。
 * 待旧客户端全部下线后可整体删除本控制器。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Slf4j
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 对话", description = "AI Agent 流式对话接口")
public class AgentChatController {

    private final AgentOrchestrator agentOrchestrator;
    private final ExecutorService executor;

    public AgentChatController(AgentOrchestrator agentOrchestrator,
                               @Qualifier("agentChatExecutor") ExecutorService executor) {
        this.agentOrchestrator = agentOrchestrator;
        this.executor = executor;
    }

    /**
     * 兼容旧页面的单请求 SSE；新聊天应先创建任务，再读取快照和事件流。
     *
     * @deprecated 自 2.0 起由 {@link AgentTaskController} 的
     * {@code POST /agent/tasks}、{@code GET /agent/tasks/{taskId}/snapshot} 与
     * {@code GET /agent/tasks/{taskId}/events} 替代。
     */
    @Deprecated(since = "2.0", forRemoval = false)
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "旧版 Agent 流式对话（已重构）", deprecated = true,
               description = "主聊天已迁移到 POST /agent/tasks 创建任务、" +
                       "GET /agent/tasks/{taskId}/snapshot 对齐快照、" +
                       "GET /agent/tasks/{taskId}/events 按 Last-Event-ID 续传。" +
                       "此旧接口仅为旧客户端保留兼容行为；断线重连请使用新接口。")
    public SseEmitter chatStream(@RequestBody AgentChatRequest request) {
        // 从 JWT 获取 userId
        Long userId = getCurrentUserId();
        request.setUserId(userId);
        // 旧接口不接收客户端指定的任务或消息标识，避免事件写入其他任务。
        request.setTaskId(null);
        request.setMessageId(null);

        SseEmitter emitter = new SseEmitter(180_000L);
        log.info("[AgentChat] SSE 会话开始: userId={}, cid={}, message={}",
                userId, request.getConversationId(), request.getMessage());

        executor.execute(() -> {
            agentOrchestrator.handle(request, emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("[AgentChat] SSE 超时: cid={}", request.getConversationId());
            emitter.complete();
        });
        emitter.onError(ex -> {
            log.error("[AgentChat] SSE 连接错误: cid={}", request.getConversationId(), ex);
        });

        return emitter;
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
