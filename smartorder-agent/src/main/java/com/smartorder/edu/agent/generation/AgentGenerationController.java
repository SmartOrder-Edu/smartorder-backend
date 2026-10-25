package com.smartorder.edu.agent.generation;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
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
 * Agent 生成任务接口——发送与订阅分离，支持断线重连与按游标回放。
 *
 * <p>协议：</p>
 * <ul>
 *   <li>{@code POST /agent/generations} 发送消息：同步完成事务 A 并返回
 *       conversationId / userMessageId / assistantMessageId / generationId 等 ID，
 *       生成在返回后异步执行；同一 clientRequestId 幂等复用；</li>
 *   <li>{@code GET /agent/generations/{id}/events?after=N} 订阅 SSE，从 seq &gt; N 开始回放；</li>
 *   <li>{@code GET /agent/generations/{id}} 查询状态（断线后兜底）；</li>
 *   <li>{@code POST /agent/generations/{id}/cancel} 协作式取消；</li>
 *   <li>{@code GET /agent/generations/conversations/{cid}/active} 刷新页面后的恢复入口。</li>
 * </ul>
 *
 * <p>订阅接口在建立流之前同步校验归属与游标，失败时返回真实 HTTP 状态码
 * （403/404/410），前端据此决定“重新登录 / 放弃重试 / 提示记录已过期”；
 * 校验通过后立刻返回 emitter，回放与实时接入在生成线程池上异步执行，
 * 浏览器不需要等整个 backlog 回放完才收到首字节。</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent/generations")
@RequiredArgsConstructor
@Tag(name = "Agent 生成任务", description = "发送消息（返回会话与消息 ID）、SSE 订阅回放、状态与取消")
public class AgentGenerationController {

    private final AgentGenerationService generationService;

    @PostMapping
    @Operation(summary = "发送消息并创建生成任务（同步返回 conversationId / messageId，按 clientRequestId 幂等）")
    public Result<AgentGenerationVO> create(@RequestBody AgentChatRequest request) {
        Long userId = currentUserId();
        request.setUserId(userId);
        AgentGenerationVO vo = generationService.create(request, userId, request.getClientRequestId());
        return Result.success(vo);
    }

    @GetMapping(value = "/{generationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅并回放生成事件（SSE）",
            description = "after 为客户端已成功应用的最大 seq；支持 Last-Event-ID，二者冲突时返回 400")
    public ResponseEntity<SseEmitter> subscribe(
            @PathVariable String generationId,
            @RequestParam(value = "after", required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Long userId = currentUserId();

        long cursor;
        try {
            cursor = resolveCursor(after, lastEventId);
        } catch (IllegalArgumentException e) {
            log.info("[GenerationApi] 游标非法: generationId={}, after={}, lastEventId={}",
                    generationId, after, lastEventId);
            return ResponseEntity.badRequest().build();
        }

        SseEmitter emitter = new SseEmitter(0L);
        AgentGenerationSubscriptionPlan plan;
        try {
            // 同步阶段只做鉴权/游标校验；这一步不能写连接，否则 403/404/410 就来不及了
            plan = generationService.validateSubscription(generationId, userId, cursor,
                    new SseEmitterEventSink(emitter));
        } catch (AgentGenerationException e) {
            log.info("[GenerationApi] 订阅被拒绝: generationId={}, after={}, status={}, reason={}",
                    generationId, cursor, e.getStatus().value(), e.getMessage());
            return ResponseEntity.status(e.getStatus()).build();
        }

        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> log.debug("[GenerationApi] 订阅连接断开: generationId={}, reason={}",
                generationId, ex.getMessage()));

        try {
            // 回放与实时接入在生成线程池上执行：控制器立刻返回，首字节不再等 backlog。
            // attach 可能先于本方法返回就开始写；SseEmitter 会缓存 early send，
            // 在响应初始化后按序 flush，因此不会丢回放事件。
            generationService.attachAsync(plan);
        } catch (RejectedExecutionException e) {
            log.warn("[GenerationApi] 订阅线程池已满，拒绝接入: generationId={}", generationId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    @GetMapping("/{generationId}")
    @Operation(summary = "查询生成任务状态")
    public Result<AgentGenerationVO> status(@PathVariable String generationId) {
        return Result.success(generationService.status(generationId, currentUserId()));
    }

    @PostMapping("/{generationId}/cancel")
    @Operation(summary = "取消生成任务（协作式）")
    public Result<Map<String, Object>> cancel(@PathVariable String generationId) {
        return Result.success(generationService.cancel(generationId, currentUserId()));
    }

    @GetMapping("/conversations/{conversationId}/active")
    @Operation(summary = "查询会话当前生成（刷新页面恢复用）")
    public Result<AgentGenerationVO> activeGeneration(@PathVariable String conversationId) {
        return Result.success(generationService.activeGeneration(conversationId, currentUserId()));
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
