package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.model.dto.AgentTaskSubscribeQuery;
import com.smartorder.edu.agent.model.entity.AgentChatTask;
import com.smartorder.edu.agent.service.AgentTaskService;
import com.smartorder.edu.agent.service.AgentTaskStreamService;
import com.smartorder.edu.agent.stream.AgentEventLog;
import com.smartorder.edu.agent.stream.AgentStreamCursor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * SSE 订阅读取循环实现。
 * <p>
 * 连接级错误只终止本订阅者：Agent 任务与 Redis 事件日志继续运行，客户端重连后
 * 凭 {@code Last-Event-ID} 续读即可。因此这里不注册"断开即取消任务"的回调。
 */
@Slf4j
@Service
public class AgentTaskStreamServiceImpl implements AgentTaskStreamService {

    /** 收到任一终态事件即结束本次订阅。 */
    private static final Set<String> TERMINAL_EVENTS =
            Set.of("task_completed", "task_failed", "task_cancelled");

    private final AgentTaskService taskService;
    private final AgentEventLog events;
    private final Executor streamExecutor;

    public AgentTaskStreamServiceImpl(AgentTaskService taskService,
                                      AgentEventLog events,
                                      @Qualifier("agentStreamExecutor") Executor streamExecutor) {
        this.taskService = taskService;
        this.events = events;
        this.streamExecutor = streamExecutor;
    }

    @Override
    public SseEmitter subscribe(String taskId, AgentTaskSubscribeQuery query, String lastEventId, AgentCaller caller) {
        if (query == null || query.getStreamVersion() == null || query.getStreamVersion().isBlank()) {
            throw new IllegalArgumentException("streamVersion 不能为空");
        }
        // 归属校验在调用线程完成，鉴权失败仍能返回 HTTP 状态码而不是空流。
        AgentChatTask task = taskService.requireOwned(taskId, caller);
        validateMessageId(task, query.getMessageId());
        String cursor = AgentStreamCursor.choose(lastEventId, query.getAfter());

        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> readLoop(emitter, taskId, caller, query.getStreamVersion(), cursor, task));
        return emitter;
    }

    /** 消息标识与任务绑定；允许旧客户端不传，传入时必须严格匹配。 */
    void validateMessageId(AgentChatTask task, String messageId) {
        if (messageId != null && !messageId.equals(task.getMessageId())) {
            throw new IllegalArgumentException("messageId 与 taskId 不匹配");
        }
    }

    /**
     * 读取循环：先对齐世代与日志可用性，再按游标增量推送，空闲时发心跳注释保活。
     * <p>
     * 客户端断开后写入必然抛 {@link IOException}，此时不得再次 complete，
     * 否则会刷新已损坏的连接并掩盖真实断连原因。
     */
    void readLoop(SseEmitter emitter, String taskId, AgentCaller caller, String version,
                  String cursor, AgentChatTask initial) {
        try {
            if (!version.equals(initial.getStreamVersion())) {
                resync(emitter, taskId, version, "STREAM_VERSION_MISMATCH");
                return;
            }
            if (events.isUnavailable(taskId, initial.eventCountOrZero())) {
                resync(emitter, taskId, version, "EVENT_LOG_UNAVAILABLE");
                return;
            }
            String next = cursor;
            while (true) {
                var batch = events.read(taskId, next);
                for (var record : batch) {
                    String id = record.getId().getValue();
                    // XREAD 可能重复返回边界记录，游标不前进的事件一律丢弃。
                    if (AgentStreamCursor.compare(id, next) <= 0) {
                        continue;
                    }
                    String type = String.valueOf(record.getValue().get("type"));
                    emitter.send(SseEmitter.event().id(id).name(type).data(events.envelope(record).toJSONString()));
                    next = id;
                    if (TERMINAL_EVENTS.contains(type)) {
                        emitter.complete();
                        return;
                    }
                }
                AgentChatTask task = taskService.requireOwned(taskId, caller);
                if (batch.isEmpty() && AgentChatTask.isTerminal(task.getStatus())) {
                    resync(emitter, taskId, version, "SNAPSHOT_REQUIRED");
                    return;
                }
                if (batch.isEmpty() && events.isUnavailable(taskId, task.eventCountOrZero())) {
                    resync(emitter, taskId, version, "EVENT_LOG_UNAVAILABLE");
                    return;
                }
                emitter.send(SseEmitter.event().comment("ping"));
            }
        } catch (IOException disconnected) {
            // 写入失败由 Servlet 容器关闭响应；再次 complete 会刷新已损坏的连接。
            log.debug("[AgentStream] 客户端已断开: taskId={}", taskId);
        } catch (Exception failure) {
            // 服务端读取失败时通知客户端重新对齐；连接已断则交由容器清理。
            log.warn("[AgentStream] 事件读取失败: taskId={}", taskId, failure);
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
}
