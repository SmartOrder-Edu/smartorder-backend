package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.config.AgentSseProperties;
import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.model.vo.AgentTaskVO;
import com.smartorder.edu.agent.runtime.store.AgentTaskEventStore;
import com.smartorder.edu.agent.runtime.store.InMemoryAgentTaskEventStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成任务注册表的端到端语义测试（内存归档，不依赖 Spring/Redis）：
 * 断线重连补发、进程重启后回放、游标越界、归属校验、执行者消失后的中断收敛。
 */
class AgentTaskRegistryTest {

    @Test
    void shouldReplayBufferedEventsThenLiveTailAfterReconnect() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);

        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "你"));
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "好"));

        RecordingSink first = new RecordingSink();
        validateAndAttach(fixture.registry, task.getTaskId(), 7L, 0L, first);
        assertEquals(List.of(1L, 2L), first.seqs());
        long appliedCursor = first.handledSeq;

        // 断线期间继续产生事件：生成不受连接影响
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "！"));

        // 重连：带断开时已应用的游标，只补发之后产生的事件
        RecordingSink retried = new RecordingSink();
        validateAndAttach(fixture.registry, task.getTaskId(), 7L, appliedCursor, retried);
        assertEquals(List.of(3L), retried.seqs());
    }

    @Test
    void shouldReplayFromArchiveAfterProcessRestart() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "回答"));
        fixture.registry.finish(context, Map.of("conversationId", "c-1"),
                AgentTaskStatus.COMPLETED, null);

        // 新实例 = 进程重启：内存里没有运行时对象，只能从归档回放
        AgentTaskRegistry restarted = fixture.restartedRegistry();
        RecordingSink sink = new RecordingSink();
        validateAndAttach(restarted, task.getTaskId(), 7L, 0L, sink);

        assertEquals(List.of(1L, 2L), sink.seqs());
        assertEquals("done", sink.events.get(1).getEventName());
        assertTrue(sink.completed);

        AgentTaskVO status = restarted.status(task.getTaskId(), 7L);
        assertEquals("COMPLETED", status.getStatus());
        assertEquals(2L, status.getLastSeq());
    }

    @Test
    void shouldMarkStaleRunningGenerationAsInterrupted() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "半句"));

        // 进程重启：元数据仍是 RUNNING，但已经没有执行者
        AgentTaskRegistry restarted = fixture.restartedRegistry();
        RecordingSink sink = new RecordingSink();
        validateAndAttach(restarted, task.getTaskId(), 7L, 0L, sink);

        assertEquals(List.of("agent.text.delta", "agent.error", "done"), sink.eventNames());
        assertTrue(sink.completed);
        assertEquals("INTERRUPTED", restarted.status(task.getTaskId(), 7L).getStatus());
    }

    @Test
    void shouldValidateWithoutWritingAnyBytesThenAttach() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "你"));
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "好"));

        RecordingSink sink = new RecordingSink();
        AgentTaskSubscriptionPlan plan = fixture.registry.validateSubscription(
                task.getTaskId(), 7L, 0L, sink);

        // validate 必须只读：控制器返回 emitter 之前不能有任何回放字节
        assertTrue(sink.events.isEmpty());

        // 校验与 attach 之间产生的新事件不能漏：attach 会在同一把锁里回放并登记
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "！"));

        fixture.registry.attach(plan);
        assertEquals(List.of(1L, 2L, 3L), sink.seqs());
    }

    @Test
    void shouldDeferStaleInterruptUntilAttach() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "半句"));

        // 进程重启：元数据仍停在 CREATED/RUNNING，但本机没有执行者
        AgentTaskRegistry restarted = fixture.restartedRegistry();
        RecordingSink sink = new RecordingSink();
        AgentTaskSubscriptionPlan plan = restarted.validateSubscription(
                task.getTaskId(), 7L, 0L, sink);

        assertTrue(sink.events.isEmpty(), "校验阶段不能补发任何事件");
        assertFalse(AgentTaskStatus.fromName(
                        restarted.status(task.getTaskId(), 7L).getStatus()).isTerminal(),
                "校验阶段不能提前把生成标成中断");

        restarted.attach(plan);
        assertEquals(List.of("agent.text.delta", "agent.error", "done"), sink.eventNames());
        assertTrue(sink.completed);
        assertEquals("INTERRUPTED", restarted.status(task.getTaskId(), 7L).getStatus());
    }

    @Test
    void shouldInterruptAtArchiveTailWhenMetaLastSeqIsStale() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        String taskId = task.getTaskId();
        AgentRuntimeContext context = fixture.context(taskId);
        fixture.registry.bindContext(taskId, context);
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "半句"));

        AgentTaskRegistry restarted = fixture.restartedRegistry();
        RecordingSink sink = new RecordingSink();
        // 客户端重启前已经应用到 seq=1，不能因为元数据滞后被判成“游标超前”
        AgentTaskSubscriptionPlan plan = restarted.validateSubscription(
                taskId, 7L, 1L, sink);
        restarted.attach(plan);

        assertEquals(List.of(2L, 3L), sink.seqs(),
                "中断事件必须从归档尾部之后编号，不能与已有 seq 撞号");
        assertEquals(List.of("agent.error", "done"), sink.eventNames());
        assertEquals("INTERRUPTED", restarted.status(taskId, 7L).getStatus());
    }

    @Test
    void shouldReturnStartingForFreshUnboundReservation() {
        Fixture fixture = new Fixture();
        String taskId = java.util.UUID.randomUUID().toString();
        AgentTaskRegistry.Reservation reservation =
                fixture.registry.reserve(7L, taskId, "key-start", "c-2", null);

        RecordingSink sink = new RecordingSink();
        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> fixture.registry.validateSubscription(reservation.taskId(), 7L, 0L, sink));

        // 另一个请求正在 openReply：不能让订阅把它标成中断
        assertEquals(503, error.getStatus().value());
        assertEquals("TASK_STARTING", error.getCode());
        assertTrue(sink.events.isEmpty());
        assertFalse(fixture.tasks.find(reservation.taskId()).statusEnum().isTerminal());
    }

    @Test
    void shouldRejectArchiveGapDuringValidation() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        String taskId = task.getTaskId();
        // 模拟归档缺了一条：seq=1、seq=3，游标 1 之后出现断档
        fixture.store.appendEvent(taskId,
                new AgentTaskEvent(1L, "agent.text.delta", "{}", 1L));
        fixture.store.appendEvent(taskId,
                new AgentTaskEvent(3L, "agent.text.delta", "{}", 3L));

        AgentTaskRegistry restarted = fixture.restartedRegistry();
        RecordingSink sink = new RecordingSink();
        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> restarted.validateSubscription(taskId, 7L, 1L, sink));

        assertEquals(410, error.getStatus().value());
        assertEquals("CURSOR_EXPIRED", error.getCode());
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void shouldRejectCursorAheadOfLastSeq() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> validateAndAttach(fixture.registry, task.getTaskId(), 7L, 99L,
                        new RecordingSink()));

        assertEquals(400, error.getStatus().value());
        assertEquals("INVALID_CURSOR", error.getCode());
    }

    @Test
    void shouldRejectSubscriptionFromAnotherUser() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> validateAndAttach(fixture.registry, task.getTaskId(), 8L, 0L,
                        new RecordingSink()));

        assertEquals(403, error.getStatus().value());
    }

    @Test
    void shouldRejectUnknownGeneration() {
        Fixture fixture = new Fixture();
        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> validateAndAttach(fixture.registry, "not-exists", 7L, 0L, new RecordingSink()));
        assertEquals(404, error.getStatus().value());
    }

    @Test
    void shouldKeepGeneratingWhenSubscriberDies() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);

        validateAndAttach(fixture.registry, task.getTaskId(), 7L, 0L,
                new BrokenEventSink());
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "断线前"));
        fixture.registry.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "断线后"));

        RecordingSink late = new RecordingSink();
        validateAndAttach(fixture.registry, task.getTaskId(), 7L, 0L, late);
        assertEquals(2, late.events.size(), "连接断开不应丢事件，后接入的订阅者应能完整回放");
    }

    @Test
    void shouldCancelRunningGeneration() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();
        AgentRuntimeContext context = fixture.context(task.getTaskId());
        fixture.registry.bindContext(task.getTaskId(), context);

        assertTrue(fixture.registry.cancel(task.getTaskId(), 7L));
        assertTrue(context.isCancelRequested());

        fixture.registry.finish(context, Map.of(), AgentTaskStatus.CANCELLED, null);
        assertFalse(fixture.registry.cancel(task.getTaskId(), 7L));
        assertEquals("CANCELLED", fixture.registry.status(task.getTaskId(), 7L).getStatus());
    }

    @Test
    void shouldIndexIdempotencyKeyAndConversation() {
        Fixture fixture = new Fixture();
        AgentTask task = fixture.create();

        assertEquals(task.getTaskId(),
                fixture.registry.findTaskIdByClientRequest(7L, "key-1"));
        assertNull(fixture.registry.findTaskIdByClientRequest(8L, "key-1"));
        assertNotNull(fixture.registry.activeTask("c-1", 7L));
        assertNull(fixture.registry.activeTask("c-1", 8L));
    }

    /** 测试用同步订阅：validate（校验）+ attach（回放/接入），与控制器两阶段路径一致。 */
    private static void validateAndAttach(AgentTaskRegistry registry, String taskId,
                                          Long userId, long afterSeq, AgentEventSink sink) {
        registry.attach(registry.validateSubscription(taskId, userId, afterSeq, sink));
    }

    /**
     * 每个测试独立的「存储 + 注册表」组合；restartedRegistry 复用同一个存储模拟进程重启。
     */
    private static final class Fixture {

        private final AgentTaskEventStore store = new InMemoryAgentTaskEventStore();

        private final InMemoryAgentTaskMetaService tasks = new InMemoryAgentTaskMetaService();

        private final AgentTaskRegistry registry = newManager(store, tasks);

        AgentTaskRegistry restartedRegistry() {
            return newManager(store, tasks);
        }

        AgentTask create() {
            String taskId = java.util.UUID.randomUUID().toString();
            AgentTaskRegistry.Reservation reservation = registry.reserve(7L, taskId, "key-1", "c-1", null);
            return registry.bindTurn(reservation.taskId(), 7L, "key-1", turn("c-1"));
        }

        static AgentTurnContext turn(String conversationId) {
            return AgentTurnContext.builder()
                    .traceId("t-1")
                    .conversationId(conversationId)
                    .conversationPk(1L)
                    .userMessageId(11L)
                    .assistantMessageId(12L)
                    .userSequenceNo(1)
                    .assistantSequenceNo(2)
                    .title("测试会话")
                    .titleSource("AUTO")
                    .created(true)
                    .build();
        }

        AgentRuntimeContext context(String taskId) {
            return AgentRuntimeContext.builder()
                    .taskId(taskId)
                    .traceId("t-1")
                    .conversationId("c-1")
                    .userId(7L)
                    .build();
        }

        private static AgentTaskRegistry newManager(AgentTaskEventStore store, InMemoryAgentTaskMetaService tasks) {
            // 同步镜像：测试里不启线程，直接写存储，等价于“归档一直成功”
            AgentTaskEventMirror mirror = new AgentTaskEventMirror(store, tasks) {
                @Override
                public void append(String taskId, AgentTaskEvent event) {
                    store.appendEvent(taskId, event);
                }

                @Override
                public void flush(String taskId) {
                }
            };
            return new AgentTaskRegistry(tasks, store, mirror, new AgentSseProperties());
        }
    }

    /** 模拟已断开的连接：写事件即失败 */
    private static final class BrokenEventSink implements AgentEventSink {

        @Override
        public void sendEvent(AgentTaskEvent event) {
            throw new IllegalStateException("broken pipe");
        }

        @Override
        public void sendHeartbeat() {
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable error) {
        }
    }
}
