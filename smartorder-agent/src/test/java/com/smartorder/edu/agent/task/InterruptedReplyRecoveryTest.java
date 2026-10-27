package com.smartorder.edu.agent.task;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.runtime.store.AgentTaskEventStore;
import com.smartorder.edu.agent.service.AgentTaskMetaService;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;
import com.smartorder.edu.agent.runtime.AgentTaskMeta;
import com.smartorder.edu.agent.runtime.AgentTaskRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 中断恢复的核心约束：
 * 已生成正文能从 Redis Stream 重建回 MySQL；本机仍在跑的任务不能被误判为中断。
 */
class InterruptedReplyRecoveryTest {

    private final ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);

    private final AgentTaskMetaService taskMetaService = mock(AgentTaskMetaService.class);

    private final AgentTaskEventStore eventStore = mock(AgentTaskEventStore.class);

    private final AgentTaskRegistry registry = mock(AgentTaskRegistry.class);

    private final InterruptedReplyRecovery recovery =
            new InterruptedReplyRecovery(messageMapper, taskMetaService, eventStore, registry);

    @Test
    void shouldRebuildPartialContentFromArchive() {
        ConversationMessage row = runningRow(1L, "c-1");
        when(messageMapper.selectRunningAssistantMessages()).thenReturn(List.of(row));
        when(taskMetaService.find("task-1")).thenReturn(meta("task-1", 1L, "RUNNING"));
        when(eventStore.readAfter("task-1", 0L)).thenReturn(List.of(
                textEvent(1, "你好"),
                textEvent(2, "，这是"),
                // 非正文事件必须被忽略
                AgentTaskEvent.builder().seq(3).eventName("agent.tool.started")
                        .data("{\"payload\":{\"text\":\"不该被拼进来\"}}").build(),
                textEvent(4, "部分回答")));

        recovery.recoverOnStartup();

        verify(messageMapper).markInterrupted(eq(1L), eq("你好，这是部分回答"), anyString());
    }

    @Test
    void shouldSkipTaskStillRunningInThisJvm() {
        ConversationMessage row = runningRow(1L, "c-1");
        when(messageMapper.selectRunningAssistantMessages()).thenReturn(List.of(row));
        when(taskMetaService.find("task-1")).thenReturn(meta("task-1", 1L, "RUNNING"));
        when(registry.isExecuting("task-1")).thenReturn(true);

        recovery.recoverOnStartup();

        verify(messageMapper, never()).markInterrupted(eq(1L), anyString(), anyString());
    }

    @Test
    void shouldCloseRowWithoutContentWhenTaskIsUnknown() {
        ConversationMessage row = runningRow(1L, "c-1");
        when(messageMapper.selectRunningAssistantMessages()).thenReturn(List.of(row));

        recovery.recoverOnStartup();

        verify(messageMapper).markInterrupted(eq(1L), isNull(), anyString());
        verifyNoInteractions(registry);
    }

    @Test
    void shouldNotWriteForeignContentWhenTaskPointsToAnotherMessage() {
        ConversationMessage row = runningRow(1L, "c-1");
        when(messageMapper.selectRunningAssistantMessages()).thenReturn(List.of(row));
        when(taskMetaService.find("task-1")).thenReturn(meta("task-1", 2L, "RUNNING"));

        recovery.recoverOnStartup();

        // task_id 指向的是另一条占位消息：宁可只标失败，也不能把别人的回答写进来
        verify(messageMapper).markInterrupted(eq(1L), isNull(), anyString());
    }

    @Test
    void shouldCompleteRowWhenTaskAlreadyTerminal() {
        ConversationMessage row = runningRow(1L, "c-1");
        when(messageMapper.selectRunningAssistantMessages()).thenReturn(List.of(row));
        when(taskMetaService.find("task-1")).thenReturn(meta("task-1", 1L, "COMPLETED"));
        when(eventStore.readAfter("task-1", 0L)).thenReturn(List.of(textEvent(1, "完整回答")));

        recovery.recoverOnStartup();

        verify(messageMapper).markRecoveredCompleted(1L, "完整回答");
        verify(messageMapper, never()).markInterrupted(eq(1L), anyString(), anyString());
    }

    @Test
    void shouldIgnoreBrokenEventPayload() {
        ConversationMessage row = runningRow(1L, "c-1");
        when(messageMapper.selectRunningAssistantMessages()).thenReturn(List.of(row));
        when(taskMetaService.find("task-1")).thenReturn(meta("task-1", 1L, "RUNNING"));
        when(eventStore.readAfter("task-1", 0L)).thenReturn(List.of(
                AgentTaskEvent.builder().seq(1).eventName("agent.text.delta").data("not-json").build(),
                textEvent(2, "还能恢复")));

        recovery.recoverOnStartup();

        verify(messageMapper).markInterrupted(eq(1L), eq("还能恢复"), anyString());
    }

    private ConversationMessage runningRow(Long id, String conversationId) {
        ConversationMessage row = new ConversationMessage();
        row.setId(id);
        row.setConversationId(conversationId);
        row.setRole("assistant");
        row.setStatus(ConversationMessage.STATUS_RUNNING);
        row.setTaskId("task-1");
        return row;
    }

    private AgentTaskMeta meta(String taskId, Long assistantMessageId, String status) {
        return AgentTaskMeta.builder()
                .taskId(taskId)
                .conversationId("c-1")
                .assistantMessageId(assistantMessageId)
                .status(status)
                .build();
    }

    private AgentTaskEvent textEvent(long seq, String text) {
        String data = JSON.toJSONString(Map.of(
                "taskId", "task-1",
                "eventType", "agent.text.delta",
                "seq", seq,
                "payload", Map.of("text", text)));
        return AgentTaskEvent.builder()
                .seq(seq)
                .eventName("agent.text.delta")
                .data(data)
                .build();
    }
}
