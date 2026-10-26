package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.config.AgentSseProperties;
import com.smartorder.edu.agent.exception.AgentGenerationException;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.AgentTurnContext;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentGenerationStatus;
import com.smartorder.edu.agent.model.vo.AgentGenerationVO;
import com.smartorder.edu.agent.repository.AgentGenerationEventStore;
import com.smartorder.edu.agent.repository.impl.InMemoryAgentGenerationEventStore;
import com.smartorder.edu.agent.runtime.AgentGeneration;
import com.smartorder.edu.agent.runtime.AgentGenerationEvent;
import com.smartorder.edu.agent.runtime.AgentGenerationEventMirror;
import com.smartorder.edu.agent.runtime.AgentGenerationRegistry;
import com.smartorder.edu.agent.runtime.AgentGenerationSubscriptionPlan;
import com.smartorder.edu.agent.runtime.RecordingSink;
import com.smartorder.edu.agent.service.AgentChatService;
import com.smartorder.edu.agent.service.AgentOrchestrator;
import com.smartorder.edu.agent.service.ConversationMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 发送接口的语义：同步开轮并返回会话/消息 ID；同一 clientRequestId 只执行一次业务。
 */
class AgentChatServiceImplTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldReturnConversationAndMessageIdsBeforeStreaming() {
        AgentGenerationEventStore store = new InMemoryAgentGenerationEventStore();
        AgentGenerationRegistry manager = newManager(store);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        when(messageService.openReply(any(), any(), any(), any(), any())).thenReturn(turnState());
        AgentChatService service = new AgentChatServiceImpl(manager, orchestrator, messageService, executor);

        AgentGenerationVO vo = service.sendMessage(request("你好"), 7L, "key-0");

        // 发送响应里必须已经带齐会话与消息 ID，SSE 只负责后续增量
        assertTrue(vo.isCreated());
        assertEquals("c-1", vo.getConversationId());
        assertNotNull(vo.getTraceId());
        assertEquals(11L, vo.getUserMessageId());
        assertEquals(12L, vo.getAssistantMessageId());
        assertEquals(2, vo.getAssistantSequenceNo());
        assertEquals("测试会话", vo.getTitle());
        assertEquals("CREATED", vo.getStatus());

        // 编排线程拿到的是同一轮（不会在异步阶段再开一次轮次）
        verify(orchestrator, timeout(2000))
                .handle(any(), isNull(), eq(vo.getGenerationId()), any(AgentTurnContext.class));
        verify(messageService, timeout(2000)).openReply(any(), eq(7L), eq("你好"), any(), anyString());
    }

    @Test
    void shouldReuseGenerationForSameClientRequestId() {
        AgentGenerationEventStore store = new InMemoryAgentGenerationEventStore();
        AgentGenerationRegistry manager = newManager(store);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        when(messageService.openReply(any(), any(), any(), any(), any())).thenReturn(turnState());
        AgentChatService service = new AgentChatServiceImpl(manager, orchestrator, messageService, executor);

        AgentGenerationVO first = service.sendMessage(request("你好"), 7L, "key-1");
        AgentGenerationVO second = service.sendMessage(request("你好"), 7L, "key-1");

        assertTrue(first.isCreated());
        assertFalse(second.isCreated());
        assertEquals(first.getGenerationId(), second.getGenerationId());
        assertEquals("c-1", second.getConversationId());
        assertEquals(12L, second.getAssistantMessageId());

        // 响应丢失后重试：不重复开轮、不重复执行业务
        verify(orchestrator, timeout(2000).times(1))
                .handle(any(), isNull(), eq(first.getGenerationId()), any(AgentTurnContext.class));
        verify(messageService, timeout(2000).times(1)).openReply(any(), any(), any(), any(), any());
    }

    @Test
    void shouldFailFastWhenTurnCannotBeOpened() {
        AgentGenerationEventStore store = new InMemoryAgentGenerationEventStore();
        AgentGenerationRegistry manager = newManager(store);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        when(messageService.openReply(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("会话已删除或已归档"));
        AgentChatService service = new AgentChatServiceImpl(manager, orchestrator, messageService, executor);

        AgentGenerationException error = assertThrows(AgentGenerationException.class,
                () -> service.sendMessage(request("你好"), 7L, "key-3"));

        assertEquals("TURN_OPEN_FAILED", error.getCode());
        assertTrue(error.getMessage().contains("会话已删除"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void shouldFailGenerationWhenOrchestratorThrows() throws Exception {
        AgentGenerationEventStore store = new InMemoryAgentGenerationEventStore();
        AgentGenerationRegistry manager = newManager(store);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        when(messageService.openReply(any(), any(), any(), any(), any())).thenReturn(turnState());
        doThrow(new IllegalStateException("编排炸了")).when(orchestrator)
                .handle(any(), isNull(), anyString(), any());
        AgentChatService service = new AgentChatServiceImpl(manager, orchestrator, messageService, executor);

        AgentGenerationVO vo = service.sendMessage(request("你好"), 7L, "key-2");

        AgentGenerationVO status = awaitTerminal(service, vo.getGenerationId());
        assertEquals("FAILED", status.getStatus());
        assertEquals("编排炸了", status.getErrorMessage());

        // 终态生成仍可回放，且回放里一定有 done
        RecordingSink sink = new RecordingSink();
        manager.attach(manager.validateSubscription(vo.getGenerationId(), 7L, 0L, sink));
        assertTrue(sink.eventNames().contains("done"));
        assertTrue(sink.completed);
    }

    @Test
    void shouldDeferAttachToGenerationExecutor() {
        AgentGenerationEventStore store = new InMemoryAgentGenerationEventStore();
        AgentGenerationRegistry manager = newManager(store);
        AgentGeneration generation = manager.create(7L, "key-async",
                AgentTurnContext.from(turnState(), "t-1"));
        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .generationId(generation.getGenerationId())
                .traceId("t-1")
                .conversationId("c-1")
                .userId(7L)
                .build();
        manager.bindContext(generation.getGenerationId(), context);
        manager.publish(context, AgentEventType.TEXT_DELTA, Map.of("text", "你好"));

        ExecutorService deferredExecutor = mock(ExecutorService.class);
        AgentChatService service = new AgentChatServiceImpl(
                manager, mock(AgentOrchestrator.class), mock(ConversationMessageService.class), deferredExecutor);

        RecordingSink sink = new RecordingSink();
        AgentGenerationSubscriptionPlan plan = service.validateSubscription(
                generation.getGenerationId(), 7L, 0L, sink);
        assertTrue(sink.events.isEmpty());

        service.attachAsync(plan);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(deferredExecutor).execute(task.capture());
        assertTrue(sink.events.isEmpty(), "attachAsync 只应提交任务，回放必须发生在执行线程");

        task.getValue().run();
        assertEquals(1, sink.events.size());
    }

    private AgentGenerationVO awaitTerminal(AgentChatService service, String generationId)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            AgentGenerationVO status = service.status(generationId, 7L);
            if (AgentGenerationStatus.fromName(status.getStatus()).isTerminal()) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("生成未在预期时间内收敛: " + generationId);
    }

    private ConversationMessageService.PendingReply turnState() {
        return ConversationMessageService.PendingReply.builder()
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
    }

    private AgentChatRequest request(String message) {
        AgentChatRequest request = new AgentChatRequest();
        request.setUserId(7L);
        request.setMessage(message);
        request.setConversationId("c-1");
        return request;
    }

    private AgentGenerationRegistry newManager(AgentGenerationEventStore store) {
        AgentGenerationEventMirror mirror = new AgentGenerationEventMirror(store) {
            @Override
            public void append(String generationId, AgentGenerationEvent event) {
                store.appendEvent(generationId, event);
            }

            @Override
            public void flush(String generationId) {
            }
        };
        return new AgentGenerationRegistry(store, mirror, new AgentSseProperties());
    }
}
