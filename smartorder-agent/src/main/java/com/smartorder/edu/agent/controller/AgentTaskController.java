package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.repository.impl.AgentChatEventLog;
import com.smartorder.edu.agent.service.AgentTaskService;
import com.smartorder.edu.agent.util.AgentStreamCursor;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

/** 新版可恢复聊天接口；POST 唯一创建任务，所有 GET 均为只读。 */
@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskController {
    private final AgentTaskService tasks;
    private final AgentChatEventLog events;
    private final Executor streamExecutor;

    /** 无效请求游标或幂等键返回 400，避免进入 SSE 静默重试。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<String> badRequest(IllegalArgumentException error) {
        return new Result<>(400, error.getMessage(), null);
    }

    public AgentTaskController(AgentTaskService tasks, AgentChatEventLog events,
                               @Qualifier("agentStreamExecutor") Executor streamExecutor) {
        this.tasks = tasks;
        this.events = events;
        this.streamExecutor = streamExecutor;
    }

    /** 请求幂等键由客户端跨网络重试复用；服务端唯一索引阻止重复执行。 */
    @PostMapping
    public ResponseEntity<Result<Map<String, Object>>> create(@RequestBody AgentChatRequest request) {
        Claims identity = ThreadLocalUtils.get();
        return ResponseEntity.accepted().body(Result.success(tasks.create(request,
                identity.get("userId", Long.class), tenant(identity))));
    }

    /** 返回同一数据库行上的内容投影与事件游标。 */
    @GetMapping("/{taskId}/snapshot")
    public Result<Map<String, Object>> snapshot(@PathVariable String taskId) {
        Claims identity = ThreadLocalUtils.get();
        return Result.success(tasks.snapshot(taskId, identity.get("userId", Long.class), tenant(identity)));
    }

    /** 网络重连只能重新读取已有 Stream，绝不启动 Agent。 */
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(@PathVariable String taskId,
                                                @RequestParam(defaultValue = "0-0") String after,
                                                @RequestParam String streamVersion,
                                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Claims identity = ThreadLocalUtils.get();
        Long userId = identity.get("userId", Long.class);
        String tenantId = tenant(identity);
        Map<String, Object> task = tasks.owned(taskId, userId, tenantId);
        String cursor = AgentStreamCursor.choose(lastEventId, after);
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> readLoop(emitter, taskId, userId, tenantId, streamVersion, cursor, task));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** 查询会话的活动任务供 F5 恢复；鉴权来自当前 JWT。 */
    @GetMapping("/active")
    public Result<Map<String, Object>> active(@RequestParam String conversationId) {
        Claims identity = ThreadLocalUtils.get();
        return Result.success(tasks.activeForConversation(conversationId,
                identity.get("userId", Long.class), tenant(identity)));
    }

    /** 缓存隔离键只从已校验 JWT 得出，前端不能自行猜测租户。 */
    @GetMapping("/cache-scope")
    public Result<Map<String, String>> cacheScope() {
        Claims identity = ThreadLocalUtils.get();
        return Result.success(Map.of("scopeKey", tenant(identity) + ":" + identity.get("userId", Long.class)));
    }

    /** 连接错误仅终止本订阅者，任务执行与 Redis 事件日志继续存在。 */
    private void readLoop(SseEmitter emitter, String taskId, Long userId, String tenantId,
                          String version, String cursor, Map<String, Object> initial) {
        try {
            if (!version.equals(initial.get("stream_version"))) {
                resync(emitter, taskId, version, "STREAM_VERSION_MISMATCH");
                return;
            }
            long count = ((Number) initial.get("event_count")).longValue();
            if (events.isUnavailable(taskId, count)) {
                resync(emitter, taskId, version, "EVENT_LOG_UNAVAILABLE");
                return;
            }
            String next = cursor;
            while (true) {
                var batch = events.read(taskId, next);
                for (var record : batch) {
                    String id = record.getId().getValue();
                    if (AgentStreamCursor.compare(id, next) <= 0) continue;
                    String type = String.valueOf(record.getValue().get("type"));
                    emitter.send(SseEmitter.event().id(id).name(type).data(events.envelope(record).toJSONString()));
                    next = id;
                    if (type.equals("task_completed") || type.equals("task_failed")
                            || type.equals("task_cancelled")) {
                        emitter.complete();
                        return;
                    }
                }
                Map<String, Object> task = tasks.owned(taskId, userId, tenantId);
                if (batch.isEmpty() && tasks.isTerminal(String.valueOf(task.get("status")))) {
                    resync(emitter, taskId, version, "SNAPSHOT_REQUIRED");
                    return;
                }
                if (batch.isEmpty() && events.isUnavailable(taskId,
                        ((Number) task.get("event_count")).longValue())) {
                    resync(emitter, taskId, version, "EVENT_LOG_UNAVAILABLE");
                    return;
                }
                emitter.send(SseEmitter.event().comment("ping"));
            }
        } catch (IOException disconnected) {
            // 写入失败由 Servlet 容器关闭响应；再次 complete 会刷新已损坏的连接。
        } catch (Exception failure) {
            // 服务端读取失败时通知客户端重新对齐；连接已断则交由容器清理。
            try {
                resync(emitter, taskId, version, "STREAM_READ_FAILED");
            } catch (IOException disconnected) {
                // 客户端已断开，Agent 任务和 Redis 日志继续运行。
            }
        }
    }

    /** 控制事件不携带 id，浏览器不能将其误当成可恢复事件游标。 */
    private void resync(SseEmitter emitter, String taskId, String version, String reason) throws IOException {
        emitter.send(SseEmitter.event().name("resync_required")
                .data(Map.of("taskId", taskId, "streamVersion", version, "reason", reason)));
        emitter.complete();
    }

    private String tenant(Claims identity) {
        Object value = identity.get("tenantId");
        return value == null ? "default" : String.valueOf(value);
    }
}
