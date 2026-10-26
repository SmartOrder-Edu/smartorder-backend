package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.vo.AgentTaskVO;
import com.smartorder.edu.agent.runtime.AgentTaskSubscriptionPlan;
import com.smartorder.edu.agent.runtime.SseEmitterEventSink;
import com.smartorder.edu.agent.service.AgentChatService;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent 对话任务接口——发送与订阅分离，支持断线重连与按游标回放。
 *
 * <p><b>客户端对接状态：移动端暂未实现。</b>本控制器是面向客户端的“发送/订阅分离 + 游标续传”协议，
 * 服务端已实现并有测试覆盖，但客户端侧目前只有 Web 端接了：
 * {@code smartorder-frontend/src/composables/useAgentChat.ts}（发送、状态、取消、活跃查询）与
 * {@code useResumableAgentStream.ts}（SSE 订阅与重连）。
 * 移动端（uni-app）仍在调用 {@code POST /agent/chat/stream} 旧兼容入口，尚未迁移到本链路，
 * 因此这些接口没有移动端消费方，联调与验收以接口文档和后端测试为准。
 * 后续移动端接入只需复用本协议（{@code taskRequestId} 幂等键 + {@code after} 游标），服务端无需改动。</p>
 *
 * <p>协议：</p>
 * <ul>
 *   <li>{@code POST /agent/tasks} 发送消息：同步完成事务 A 并返回
 *       conversationId / userMessageId / assistantMessageId / taskId 等 ID，
 *       生成在返回后异步执行；同一 requestId / conversationId + messageUuid 幂等复用；</li>
 *   <li>{@code GET /agent/tasks/{id}/events?after=N} 订阅 SSE，从 Redis Stream 回放 seq &gt; N 的消息片段；</li>
 *   <li>{@code GET /agent/tasks/{id}} 查询状态（断线后兜底）；</li>
 *   <li>{@code POST /agent/tasks/{id}/cancel} 协作式取消；</li>
 *   <li>{@code GET /agent/tasks/conversations/{cid}/active} 刷新页面后的恢复入口。</li>
 * </ul>
 *
 * <p>旧路径 {@code /agent/generations} 作为 deprecated 别名保留，映射到同一套 task 逻辑；
 * 返回体额外带 {@code generationId} 只读别名，避免尚未迁移的调用方立即 404。</p>
 *
 * <p>订阅接口在建立流之前同步校验归属与游标，失败时返回真实 HTTP 状态码
 * （403/404/410），前端据此决定“重新登录 / 放弃重试 / 提示记录已过期”；
 * 校验通过后立刻返回 emitter，回放与实时接入在生成线程池上异步执行，
 * 浏览器不需要等整个 backlog 回放完才收到首字节。</p>
 */
@Slf4j
@RestController
@RequestMapping({"/agent/tasks", "/agent/generations"})
@RequiredArgsConstructor
@Tag(name = "Agent 对话任务", description = "发送消息（返回任务与会话 ID）、SSE 订阅 Redis Stream、状态与取消")
public class AgentTaskController {

    private final AgentChatService chatService;

    // 客户端对接：移动端暂未实现——当前仅 Web 端 useAgentChat.ts 调用，uni-app 仍走旧入口
    @PostMapping
    @Operation(summary = "发送消息并创建对话任务（同步返回 conversationId / taskId / messageId，按 requestId + conversationId + messageUuid 幂等）",
            description = "新前端传 conversationId + messageUuid + draftId；draftId 与 conversationId 一对一，"
                    + "同一 requestId 换 draftId 返回 409。旧客户端可只传 conversationId/taskRequestId。")
    public Result<AgentTaskVO> create(@RequestBody AgentChatRequest request) {
        Long userId = currentUserId();
        request.setUserId(userId);
        AgentTaskVO vo = chatService.sendMessage(request, userId, request.getTaskRequestId());
        return Result.success(vo);
    }

    // 客户端对接：移动端暂未实现——当前仅 Web 端 useResumableAgentStream.ts 订阅，uni-app 未接入
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅并回放任务事件（SSE，读取 Redis Stream）",
            description = "after 为客户端已成功应用的最大 seq；支持 Last-Event-ID，二者冲突时返回 400")
    public ResponseEntity<SseEmitter> subscribe(
            @PathVariable String taskId,
            @RequestParam(value = "after", required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Long userId = currentUserId();

        long cursor;
        try {
            cursor = resolveCursor(after, lastEventId);
        } catch (IllegalArgumentException e) {
            log.info("[TaskApi] 游标非法: taskId={}, after={}, lastEventId={}",
                    taskId, after, lastEventId);
            return ResponseEntity.badRequest().build();
        }

        SseEmitter emitter = new SseEmitter(0L);
        AgentTaskSubscriptionPlan plan;
        try {
            // 同步阶段只做鉴权/游标校验；这一步不能写连接，否则 403/404/410 就来不及了
            plan = chatService.validateSubscription(taskId, userId, cursor,
                    new SseEmitterEventSink(emitter));
        } catch (AgentTaskException e) {
            log.info("[TaskApi] 订阅被拒绝: taskId={}, after={}, status={}, reason={}",
                    taskId, cursor, e.getStatus().value(), e.getMessage());
            return ResponseEntity.status(e.getStatus()).build();
        }

        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> log.debug("[TaskApi] 订阅连接断开: taskId={}, reason={}",
                taskId, ex.getMessage()));

        try {
            // 回放与实时接入在生成线程池上执行：控制器立刻返回，首字节不再等 backlog。
            // attach 可能先于本方法返回就开始写；SseEmitter 会缓存 early send，
            // 在响应初始化后按序 flush，因此不会丢回放事件。
            chatService.attachAsync(plan);
        } catch (RejectedExecutionException e) {
            log.warn("[TaskApi] 订阅线程池已满，拒绝接入: taskId={}", taskId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // 禁止代理压缩/缓冲：SSE 需要逐帧尽快到达浏览器，否则心跳与增量会被攒着发
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    // 客户端对接：移动端暂未实现——当前仅 Web 端断线兜底查询调用，uni-app 未接入
    @GetMapping("/{taskId}")
    @Operation(summary = "查询对话任务状态")
    public Result<AgentTaskVO> status(@PathVariable String taskId) {
        return Result.success(chatService.status(taskId, currentUserId()));
    }

    // 客户端对接：移动端暂未实现——当前仅 Web 端停止按钮调用，uni-app 未接入
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消对话任务（协作式）")
    public Result<Map<String, Object>> cancel(@PathVariable String taskId) {
        return Result.success(chatService.cancel(taskId, currentUserId()));
    }

    // 客户端对接：移动端暂未实现——当前仅 Web 端刷新恢复调用，uni-app 未接入
    @GetMapping("/conversations/{conversationId}/active")
    @Operation(summary = "查询会话当前任务（刷新页面恢复用）")
    public Result<AgentTaskVO> activeTask(@PathVariable String conversationId) {
        return Result.success(chatService.activeTask(conversationId, currentUserId()));
    }

    /**
     * 解析断点游标：after 与 Last-Event-ID 只能二选一，同时存在且不一致视为客户端错误。
     */
    private long resolveCursor(Long after, String lastEventId) {
        Long headerCursor = null;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                headerCursor = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Last-Event-ID 不是合法游标");
            }
        }
        if (after != null && headerCursor != null && !after.equals(headerCursor)) {
            throw new IllegalArgumentException("after 与 Last-Event-ID 冲突");
        }
        long cursor = after != null ? after : (headerCursor != null ? headerCursor : 0L);
        if (cursor < 0) {
            throw new IllegalArgumentException("游标不能为负数");
        }
        return cursor;
    }

    private Long currentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims == null ? null : claims.get("userId", Long.class);
    }
}
