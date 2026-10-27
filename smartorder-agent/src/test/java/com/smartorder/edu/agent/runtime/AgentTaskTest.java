package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.store.InMemoryAgentTaskStateStore;
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
class AgentTaskTest {

    @Test
    void shouldReplayOnlyEventsAfterCursor() {
        AgentTask task = newGeneration(null);
        AgentRuntimeContext context = context(task);
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "一"));
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "二"));
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "三"));

        RecordingSink sink = new RecordingSink();
        task.attach(new AgentTaskSubscription(task.getTaskId(), 1L, sink));

        assertEquals(List.of(2L, 3L), sink.seqs());
        assertFalse(sink.completed);
    }

    @Test
    void shouldDeliverLiveEventsAfterReplay() {
        AgentTask task = newGeneration(null);
        AgentRuntimeContext context = context(task);
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "一"));

        RecordingSink sink = new RecordingSink();
        task.attach(new AgentTaskSubscription(task.getTaskId(), 0L, sink));
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "二"));

        assertEquals(List.of(1L, 2L), sink.seqs());
    }

    @Test
    void shouldEmitDoneOnlyOnceAndCloseSubscribers() {
        AgentTask task = newGeneration(null);
        AgentRuntimeContext context = context(task);
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "一"));

        RecordingSink before = new RecordingSink();
        task.attach(new AgentTaskSubscription(task.getTaskId(), 0L, before));

        AgentTaskEvent done = task.finish(context, Map.of("conversationId", "c-1"),
                AgentTaskStatus.COMPLETED, null);
        assertEquals(2L, done.getSeq());
        assertEquals("done", done.getEventName());
        assertTrue(before.completed);
        assertEquals(List.of(1L, 2L), before.seqs());

        // 终态之后：不允许再产生事件，也不允许重复终态
        assertNull(task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "迟到")));
        assertNull(task.finish(context, Map.of(), AgentTaskStatus.FAILED, "x"));

        // 终态之后接入的新订阅者：回放全部事件（含 done）后立即关闭
        RecordingSink late = new RecordingSink();
        task.attach(new AgentTaskSubscription(task.getTaskId(), 0L, late));
        assertEquals(List.of(1L, 2L), late.seqs());
        assertTrue(late.completed);
        assertEquals("done", late.events.get(1).getEventName());
    }

    @Test
    void shouldNotReplayEphemeralEvents() {
        AgentTask task = newGeneration(null);
        AgentRuntimeContext context = context(task);
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "正文"));

        RecordingSink online = new RecordingSink();
        task.attach(new AgentTaskSubscription(task.getTaskId(), 0L, online));
        task.publishEphemeral(context, "agent.conversation.title", Map.of("title", "标题"));

        // 在线连接能收到即时事件，但它不占用 seq
        assertEquals(2, online.events.size());
        assertEquals(0L, online.events.get(1).getSeq());

        RecordingSink replayed = new RecordingSink();
        task.attach(new AgentTaskSubscription(task.getTaskId(), 0L, replayed));
        assertEquals(List.of(1L), replayed.seqs());
    }

    @Test
    void shouldKeepDonePayloadFlatForLegacyClients() {
        AgentTask task = newGeneration(null);
        AgentRuntimeContext context = context(task);
        task.append(context, AgentEventType.TEXT_DELTA, Map.of("text", "正文"));

        AgentTaskEvent done = task.finish(context,
                Map.of("conversationId", "c-1", "messageId", 12L),
                AgentTaskStatus.COMPLETED, null);

        // 历史前端直接从 done.data 读 messageId/conversationId，不能套事件信封
        com.alibaba.fastjson.JSONObject payload = com.alibaba.fastjson.JSON.parseObject(done.getData());
        assertEquals("c-1", payload.getString("conversationId"));
        assertEquals(12L, payload.getLongValue("messageId"));
        assertEquals("COMPLETED", payload.getString("status"));
        assertEquals(task.getTaskId(), payload.getString("taskId"));
        assertNull(payload.get("payload"));
    }

    @Test
    void shouldCancelThroughContext() {
        AgentTask task = newGeneration(null);
        AgentRuntimeContext context = context(task);
        task.bindContext(context);

        assertFalse(context.shouldStop());
        task.cancel();

        assertTrue(task.isCancelRequested());
        assertTrue(context.isCancelRequested());
        assertTrue(context.shouldStop());
    }

    @Test
    void shouldKeepLegacyDisconnectSemanticsOnlyWithoutGeneration() {
        AgentRuntimeContext legacy = AgentRuntimeContext.builder().traceId("t").build();
        legacy.setClientDisconnected(true);
        assertTrue(legacy.shouldStop(), "旧入口断连仍应停止执行");

        AgentRuntimeContext generated = AgentRuntimeContext.builder()
                .traceId("t").taskId("g-1").build();
        generated.setClientDisconnected(true);
        assertFalse(generated.shouldStop(), "生成任务断连只代表订阅中断，不应停止执行");
    }

    // ============ 辅助 ============

    private AgentTask newGeneration(AgentTask.EventListener listener) {
        // 状态由共享存储承载：本机对象只是时序/回放句柄，构造时给一份初始快照即可
        long now = System.currentTimeMillis();
        AgentTaskState state = AgentTaskState.builder()
                .taskId("g-test")
                .userId(7L)
                .taskRequestId("key-1")
                .conversationId("c-1")
                .traceId("t-1")
                .userMessageId(11L)
                .assistantMessageId(12L)
                .assistantSequenceNo(2)
                .title("测试会话")
                .status(AgentTaskStatus.CREATED.name())
                .createdAt(now)
                .updatedAt(now)
                .replayable(true)
                .nodeId("test-node")
                .build();
        return new AgentTask(state, new InMemoryAgentTaskStateStore(), listener);
    }

    private AgentRuntimeContext context(AgentTask task) {
        return AgentRuntimeContext.builder()
                .taskId(task.getTaskId())
                .traceId("t-1")
                .conversationId("c-1")
                .userId(7L)
                .build();
    }
}
