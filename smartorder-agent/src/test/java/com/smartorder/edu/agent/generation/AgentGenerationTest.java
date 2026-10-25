package com.smartorder.edu.agent.generation;

import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成对象的核心语义测试：seq 连续性、按游标重放、终态一次性、即时事件不入日志。
 */
class AgentGenerationTest {

    @Test
    void shouldReplayOnlyEventsAfterCursor() {
        AgentGeneration generation = newGeneration(null);
        AgentRuntimeContext context = context(generation);
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "一"));
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "二"));
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "三"));

        RecordingSink sink = new RecordingSink();
        generation.attach(new AgentGenerationSubscription(generation.getGenerationId(), 1L, sink));

        assertEquals(List.of(2L, 3L), sink.seqs());
        assertFalse(sink.completed);
    }

    @Test
    void shouldDeliverLiveEventsAfterReplay() {
        AgentGeneration generation = newGeneration(null);
        AgentRuntimeContext context = context(generation);
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "一"));

        RecordingSink sink = new RecordingSink();
        generation.attach(new AgentGenerationSubscription(generation.getGenerationId(), 0L, sink));
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "二"));

        assertEquals(List.of(1L, 2L), sink.seqs());
    }

    @Test
    void shouldEmitDoneOnlyOnceAndCloseSubscribers() {
        AgentGeneration generation = newGeneration(null);
        AgentRuntimeContext context = context(generation);
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "一"));

        RecordingSink before = new RecordingSink();
        generation.attach(new AgentGenerationSubscription(generation.getGenerationId(), 0L, before));

        AgentGenerationEvent done = generation.finish(context, Map.of("conversationId", "c-1"),
                AgentGenerationStatus.COMPLETED, null);
        assertEquals(2L, done.getSeq());
        assertEquals("done", done.getEventName());
        assertTrue(before.completed);
        assertEquals(List.of(1L, 2L), before.seqs());

        // 终态之后：不允许再产生事件，也不允许重复终态
        assertNull(generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "迟到")));
        assertNull(generation.finish(context, Map.of(), AgentGenerationStatus.FAILED, "x"));

        // 终态之后接入的新订阅者：回放全部事件（含 done）后立即关闭
        RecordingSink late = new RecordingSink();
        generation.attach(new AgentGenerationSubscription(generation.getGenerationId(), 0L, late));
        assertEquals(List.of(1L, 2L), late.seqs());
        assertTrue(late.completed);
        assertEquals("done", late.events.get(1).getEventName());
    }

    @Test
    void shouldNotReplayEphemeralEvents() {
        AgentGeneration generation = newGeneration(null);
        AgentRuntimeContext context = context(generation);
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "正文"));

        RecordingSink online = new RecordingSink();
        generation.attach(new AgentGenerationSubscription(generation.getGenerationId(), 0L, online));
        generation.publishEphemeral(context, "agent.conversation.title", Map.of("title", "标题"));

        // 在线连接能收到即时事件，但它不占用 seq
        assertEquals(2, online.events.size());
        assertEquals(0L, online.events.get(1).getSeq());

        RecordingSink replayed = new RecordingSink();
        generation.attach(new AgentGenerationSubscription(generation.getGenerationId(), 0L, replayed));
        assertEquals(List.of(1L), replayed.seqs());
    }

    @Test
    void shouldKeepDonePayloadFlatForLegacyClients() {
        AgentGeneration generation = newGeneration(null);
        AgentRuntimeContext context = context(generation);
        generation.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "正文"));

        AgentGenerationEvent done = generation.finish(context,
                Map.of("conversationId", "c-1", "messageId", 12L),
                AgentGenerationStatus.COMPLETED, null);

        // 历史前端直接从 done.data 读 messageId/conversationId，不能套事件信封
        com.alibaba.fastjson.JSONObject payload = com.alibaba.fastjson.JSON.parseObject(done.getData());
        assertEquals("c-1", payload.getString("conversationId"));
        assertEquals(12L, payload.getLongValue("messageId"));
        assertEquals("COMPLETED", payload.getString("status"));
        assertEquals(generation.getGenerationId(), payload.getString("generationId"));
        assertNull(payload.get("payload"));
    }

    @Test
    void shouldCancelThroughContext() {
        AgentGeneration generation = newGeneration(null);
        AgentRuntimeContext context = context(generation);
        generation.bindContext(context);

        assertFalse(context.shouldStop());
        generation.cancel();

        assertTrue(generation.isCancelRequested());
        assertTrue(context.isCancelRequested());
        assertTrue(context.shouldStop());
    }

    @Test
    void shouldKeepLegacyDisconnectSemanticsOnlyWithoutGeneration() {
        AgentRuntimeContext legacy = AgentRuntimeContext.builder().traceId("t").build();
        legacy.setClientDisconnected(true);
        assertTrue(legacy.shouldStop(), "旧入口断连仍应停止执行");

        AgentRuntimeContext generated = AgentRuntimeContext.builder()
                .traceId("t").generationId("g-1").build();
        generated.setClientDisconnected(true);
        assertFalse(generated.shouldStop(), "生成任务断连只代表订阅中断，不应停止执行");
    }

    // ============ 辅助 ============

    private AgentGeneration newGeneration(AgentGeneration.EventListener listener) {
        AgentTurnContext turn = AgentTurnContext.builder()
                .traceId("t-1")
                .conversationId("c-1")
                .conversationPk(1L)
                .userMessageId(11L)
                .assistantMessageId(12L)
                .userSequenceNo(1)
                .assistantSequenceNo(2)
                .title("测试会话")
                .titleSource("AUTO")
                .created(true)
                .build();
        return new AgentGeneration("g-test", 7L, "key-1", turn, System.currentTimeMillis(), listener);
    }

    private AgentRuntimeContext context(AgentGeneration generation) {
        return AgentRuntimeContext.builder()
                .generationId(generation.getGenerationId())
                .traceId("t-1")
                .conversationId("c-1")
                .userId(7L)
                .build();
    }
}
