package com.smartorder.edu.agent.generation;

import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
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
 * 生成管理器的端到端语义测试（内存归档，不依赖 Spring/Redis）：
 * 断线重连补发、进程重启后回放、游标越界、归属校验、执行者消失后的中断收敛。
 */
class AgentGenerationManagerTest {

    @Test
    void shouldReplayBufferedEventsThenLiveTailAfterReconnect() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);

        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "你"));
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "好"));

        RecordingSink first = new RecordingSink();
        validateAndAttach(fixture.manager, generation.getGenerationId(), 7L, 0L, first);
        assertEquals(List.of(1L, 2L), first.seqs());
        long appliedCursor = first.handledSeq;

        // 断线期间继续产生事件：生成不受连接影响
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "！"));

        // 重连：带断开时已应用的游标，只补发之后产生的事件
        RecordingSink retried = new RecordingSink();
        validateAndAttach(fixture.manager, generation.getGenerationId(), 7L, appliedCursor, retried);
        assertEquals(List.of(3L), retried.seqs());
    }

    @Test
    void shouldReplayFromArchiveAfterProcessRestart() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "回答"));
        fixture.manager.finish(context, Map.of("conversationId", "c-1"),
                AgentGenerationStatus.COMPLETED, null);

        // 新实例 = 进程重启：内存里没有运行时对象，只能从归档回放
        AgentGenerationManager restarted = fixture.restartedManager();
        RecordingSink sink = new RecordingSink();
        validateAndAttach(restarted, generation.getGenerationId(), 7L, 0L, sink);

        assertEquals(List.of(1L, 2L), sink.seqs());
        assertEquals("done", sink.events.get(1).getEventName());
        assertTrue(sink.completed);

        AgentGenerationVO status = restarted.status(generation.getGenerationId(), 7L);
        assertEquals("COMPLETED", status.getStatus());
        assertEquals(2L, status.getLastSeq());
    }

    @Test
    void shouldMarkStaleRunningGenerationAsInterrupted() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "半句"));

        // 进程重启：元数据仍是 RUNNING，但已经没有执行者
        AgentGenerationManager restarted = fixture.restartedManager();
        RecordingSink sink = new RecordingSink();
        validateAndAttach(restarted, generation.getGenerationId(), 7L, 0L, sink);

        assertEquals(List.of("agent.text.delta", "agent.error", "done"), sink.eventNames());
        assertTrue(sink.completed);
        assertEquals("INTERRUPTED", restarted.status(generation.getGenerationId(), 7L).getStatus());
    }

    @Test
    void shouldValidateWithoutWritingAnyBytesThenAttach() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "你"));
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "好"));

        RecordingSink sink = new RecordingSink();
        AgentGenerationSubscriptionPlan plan = fixture.manager.validateSubscription(
                generation.getGenerationId(), 7L, 0L, sink);

        // validate 必须只读：控制器返回 emitter 之前不能有任何回放字节
        assertTrue(sink.events.isEmpty());

        // 校验与 attach 之间产生的新事件不能漏：attach 会在同一把锁里回放并登记
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "！"));

        fixture.manager.attach(plan);
        assertEquals(List.of(1L, 2L, 3L), sink.seqs());
    }

    @Test
    void shouldDeferStaleInterruptUntilAttach() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "半句"));

        // 进程重启：元数据仍停在 CREATED/RUNNING，但本机没有执行者
        AgentGenerationManager restarted = fixture.restartedManager();
        RecordingSink sink = new RecordingSink();
        AgentGenerationSubscriptionPlan plan = restarted.validateSubscription(
                generation.getGenerationId(), 7L, 0L, sink);

        assertTrue(sink.events.isEmpty(), "校验阶段不能补发任何事件");
        assertFalse(AgentGenerationStatus.fromName(
                        restarted.status(generation.getGenerationId(), 7L).getStatus()).isTerminal(),
                "校验阶段不能提前把生成标成中断");

        restarted.attach(plan);
        assertEquals(List.of("agent.text.delta", "agent.error", "done"), sink.eventNames());
        assertTrue(sink.completed);
        assertEquals("INTERRUPTED", restarted.status(generation.getGenerationId(), 7L).getStatus());
    }

    @Test
    void shouldInterruptAtArchiveTailWhenMetaLastSeqIsStale() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        String generationId = generation.getGenerationId();
        AgentRuntimeContext context = fixture.context(generationId);
        fixture.manager.bindContext(generationId, context);
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "半句"));

        // 模拟 Redis 运行中的元数据：事件已经写了 seq=1，但 meta.lastSeq 还是创建时的 0
        fixture.store.getMeta(generationId).setLastSeq(0);

        AgentGenerationManager restarted = fixture.restartedManager();
        RecordingSink sink = new RecordingSink();
        // 客户端重启前已经应用到 seq=1，不能因为元数据滞后被判成“游标超前”
        AgentGenerationSubscriptionPlan plan = restarted.validateSubscription(
                generationId, 7L, 1L, sink);
        restarted.attach(plan);

        assertEquals(List.of(2L, 3L), sink.seqs(),
                "中断事件必须从归档尾部之后编号，不能与已有 seq 撞号");
        assertEquals(List.of("agent.error", "done"), sink.eventNames());
        assertEquals("INTERRUPTED", restarted.status(generationId, 7L).getStatus());
    }

    @Test
    void shouldRejectArchiveGapDuringValidation() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        String generationId = generation.getGenerationId();
        // 模拟归档缺了一条：seq=1、seq=3，游标 1 之后出现断档
        fixture.store.appendEvent(generationId,
                new AgentGenerationEvent(1L, "agent.text.delta", "{}", 1L));
        fixture.store.appendEvent(generationId,
                new AgentGenerationEvent(3L, "agent.text.delta", "{}", 3L));

        AgentGenerationManager restarted = fixture.restartedManager();
        RecordingSink sink = new RecordingSink();
        AgentGenerationException error = assertThrows(AgentGenerationException.class,
                () -> restarted.validateSubscription(generationId, 7L, 1L, sink));

        assertEquals(410, error.getStatus().value());
        assertEquals("CURSOR_EXPIRED", error.getCode());
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void shouldRejectCursorAheadOfLastSeq() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();

        AgentGenerationException error = assertThrows(AgentGenerationException.class,
                () -> validateAndAttach(fixture.manager, generation.getGenerationId(), 7L, 99L,
                        new RecordingSink()));

        assertEquals(400, error.getStatus().value());
        assertEquals("INVALID_CURSOR", error.getCode());
    }

    @Test
    void shouldRejectSubscriptionFromAnotherUser() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();

        AgentGenerationException error = assertThrows(AgentGenerationException.class,
                () -> validateAndAttach(fixture.manager, generation.getGenerationId(), 8L, 0L,
                        new RecordingSink()));

        assertEquals(403, error.getStatus().value());
    }

    @Test
    void shouldRejectUnknownGeneration() {
        Fixture fixture = new Fixture();
        AgentGenerationException error = assertThrows(AgentGenerationException.class,
                () -> validateAndAttach(fixture.manager, "not-exists", 7L, 0L, new RecordingSink()));
        assertEquals(404, error.getStatus().value());
    }

    @Test
    void shouldKeepGeneratingWhenSubscriberDies() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);

        validateAndAttach(fixture.manager, generation.getGenerationId(), 7L, 0L,
                new BrokenEventSink());
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "断线前"));
        fixture.manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "断线后"));

        RecordingSink late = new RecordingSink();
        validateAndAttach(fixture.manager, generation.getGenerationId(), 7L, 0L, late);
        assertEquals(2, late.events.size(), "连接断开不应丢事件，后接入的订阅者应能完整回放");
    }

    @Test
    void shouldCancelRunningGeneration() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();
        AgentRuntimeContext context = fixture.context(generation.getGenerationId());
        fixture.manager.bindContext(generation.getGenerationId(), context);

        assertTrue(fixture.manager.cancel(generation.getGenerationId(), 7L));
        assertTrue(context.isCancelRequested());

        fixture.manager.finish(context, Map.of(), AgentGenerationStatus.CANCELLED, null);
        assertFalse(fixture.manager.cancel(generation.getGenerationId(), 7L));
        assertEquals("CANCELLED", fixture.manager.status(generation.getGenerationId(), 7L).getStatus());
    }

    @Test
    void shouldIndexIdempotencyKeyAndConversation() {
        Fixture fixture = new Fixture();
        AgentGeneration generation = fixture.create();

        assertEquals(generation.getGenerationId(),
                fixture.manager.findGenerationIdByClientRequest(7L, "key-1"));
        assertNull(fixture.manager.findGenerationIdByClientRequest(8L, "key-1"));
        assertNotNull(fixture.manager.activeGeneration("c-1", 7L));
        assertNull(fixture.manager.activeGeneration("c-1", 8L));
    }

    /** 测试用同步订阅：validate（校验）+ attach（回放/接入），与控制器两阶段路径一致。 */
    private static void validateAndAttach(AgentGenerationManager manager, String generationId,
                                          Long userId, long afterSeq, AgentEventSink sink) {
        manager.attach(manager.validateSubscription(generationId, userId, afterSeq, sink));
    }

    /**
     * 每个测试独立的“存储 + 管理器”组合；restartedManager 复用同一个存储模拟进程重启。
     */
    private static final class Fixture {

        private final AgentGenerationEventStore store = new InMemoryAgentGenerationEventStore();

        private final AgentGenerationManager manager = newManager(store);

        AgentGenerationManager restartedManager() {
            return newManager(store);
        }

        AgentGeneration create() {
            return manager.create(7L, "key-1", turn("c-1"));
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

        AgentRuntimeContext context(String generationId) {
            return AgentRuntimeContext.builder()
                    .generationId(generationId)
                    .traceId("t-1")
                    .conversationId("c-1")
                    .userId(7L)
                    .build();
        }

        private static AgentGenerationManager newManager(AgentGenerationEventStore store) {
            // 同步镜像：测试里不启线程，直接写存储，等价于“归档一直成功”
            AgentGenerationEventMirror mirror = new AgentGenerationEventMirror(store) {
                @Override
                public void append(String generationId, AgentGenerationEvent event) {
                    store.appendEvent(generationId, event);
                }

                @Override
                public void flush(String generationId) {
                }
            };
            return new AgentGenerationManager(store, mirror, new AgentSseProperties());
        }
    }

    /** 模拟已断开的连接：写事件即失败 */
    private static final class BrokenEventSink implements AgentEventSink {

        @Override
        public void sendEvent(AgentGenerationEvent event) {
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
