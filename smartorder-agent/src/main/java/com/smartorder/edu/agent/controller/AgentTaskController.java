package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.model.dto.AgentTaskCreateRequest;
import com.smartorder.edu.agent.model.dto.AgentTaskSubscribeQuery;
import com.smartorder.edu.agent.model.vo.AgentCacheScopeVO;
import com.smartorder.edu.agent.model.vo.AgentTaskReceiptVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSnapshotVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSummaryVO;
import com.smartorder.edu.agent.service.AgentTaskService;
import com.smartorder.edu.agent.service.AgentTaskStreamService;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 可恢复 Agent 对话接口。
 * <p>
 * 本类只做 HTTP 适配：请求校验、身份解析、响应头与状态码。
 * 业务编排在 {@link AgentTaskService}，事件流读取在 {@link AgentTaskStreamService}，
 * 持久化在 {@code AgentChatTaskMapper}，Controller 不出现任何 SQL 或线程调度细节。
 * <p>
 * 接口语义：POST 是唯一创建任务的入口；所有 GET 均为只读，不会启动 Agent。
 */
@RestController
@RequestMapping("/agent/tasks")
@Tag(name = "Agent 可恢复对话", description = "先创建异步任务，再读取快照和 SSE 事件")
@RequiredArgsConstructor
public class AgentTaskController {

    private final AgentTaskService taskService;
    private final AgentTaskStreamService streamService;

    /**
     * 创建任务。
     * <p>
     * 立即返回 202 与任务回执，Agent 在后台继续执行；客户端凭 {@code requestId} 重试可拿到同一任务。
     */
    @PostMapping
    @Operation(summary = "创建 Agent 对话任务",
            description = "立即返回 conversationId、messageId、taskId；Agent 在后台继续执行")
    public ResponseEntity<Result<AgentTaskReceiptVO>> create(@Valid @RequestBody AgentTaskCreateRequest request) {
        return ResponseEntity.accepted().body(Result.success(taskService.create(request, caller())));
    }

    /** 返回同一数据库行上的内容投影与事件游标，供刷新与异常恢复。 */
    @GetMapping("/{taskId}/snapshot")
    @Operation(summary = "读取任务快照", description = "返回服务端正文、终态和已提交事件游标，供刷新与异常恢复")
    public Result<AgentTaskSnapshotVO> snapshot(@PathVariable String taskId) {
        return Result.success(taskService.snapshot(taskId, caller()));
    }

    /** 网络重连只能重新读取已有 Stream，绝不启动 Agent。 */
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅可续传 SSE", description = "携带 messageId、streamVersion 与 Last-Event-ID 读取已有事件，不创建任务")
    public ResponseEntity<SseEmitter> subscribe(@PathVariable String taskId,
                                                @Valid AgentTaskSubscribeQuery query,
                                                @RequestHeader(value = "Last-Event-ID", required = false)
                                                String lastEventId) {
        SseEmitter emitter = streamService.subscribe(taskId, query, lastEventId, caller());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                // 关闭反向代理缓冲，否则增量事件会被攒到连接结束才下发。
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** 查询会话的活动任务供 F5 恢复；鉴权来自当前 JWT。 */
    @GetMapping("/active")
    @Operation(summary = "查询会话活动任务", description = "按当前登录用户和会话 ID 查找可恢复任务")
    public Result<AgentTaskSummaryVO> active(@RequestParam String conversationId) {
        return Result.success(taskService.activeTask(conversationId, caller()));
    }

    /** 缓存隔离键只从已校验 JWT 得出，前端不能自行猜测租户。 */
    @GetMapping("/cache-scope")
    @Operation(summary = "获取本地缓存隔离范围", description = "使用当前登录用户与租户生成 IndexedDB 缓存范围")
    public Result<AgentCacheScopeVO> cacheScope() {
        return Result.success(taskService.cacheScope(caller()));
    }

    /**
     * 无效游标、幂等键复用冲突等参数问题返回 400。
     * <p>
     * 必须返回非 2xx：浏览器与 fetch 订阅逻辑据此判定"重试无意义"，避免进入 SSE 静默重试。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<String> badRequest(IllegalArgumentException error) {
        return new Result<>(HttpStatus.BAD_REQUEST.value(), error.getMessage(), null);
    }

    /** 请求体/查询参数校验失败同样是 400，不落进通用异常处理的 200 包装。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<String> invalidRequest(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("请求参数不合法");
        return new Result<>(HttpStatus.BAD_REQUEST.value(), message, null);
    }

    /** 身份只从当前请求的 JWT 声明解析，Controller 不感知用户与租户的取值细节。 */
    private AgentCaller caller() {
        Claims claims = ThreadLocalUtils.get();
        return AgentCaller.from(claims);
    }
}
