package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.memory.ConversationTitleService;
import com.smartorder.edu.agent.generation.AgentGenerationManager;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.profile.ProfileUpdateCoordinator;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.route.IntentRouteService;
import com.smartorder.edu.agent.route.RouteContext;
import com.smartorder.edu.agent.route.RoutingSource;
import com.smartorder.edu.agent.service.AgentConversationAttachmentService;
import com.smartorder.edu.agent.service.ConversationTurnService;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorImplTest {

    @Test
    void shouldOpenTurnBeforeRoutingAndExecutingReact() {
        IntentRouteService routeService = mock(IntentRouteService.class);
        SelfManagedReActEngine reActEngine = mock(SelfManagedReActEngine.class);
        PlanAndExecuteEngine planAndExecuteEngine = mock(PlanAndExecuteEngine.class);
        TraceEventDispatcher dispatcher = mock(TraceEventDispatcher.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        AgentConversationAttachmentService attachmentService = mock(AgentConversationAttachmentService.class);
        ProfileUpdateCoordinator profileUpdateCoordinator = mock(ProfileUpdateCoordinator.class);
        ConversationTurnService turnService = mock(ConversationTurnService.class);
        ConversationTitleService titleService = mock(ConversationTitleService.class);
        AgentRunSupervisor runSupervisor = mock(AgentRunSupervisor.class);
        AgentGenerationManager generationManager = mock(AgentGenerationManager.class);
        AgentOrchestratorImpl orchestrator = new AgentOrchestratorImpl(
                routeService,
                reActEngine,
                planAndExecuteEngine,
                dispatcher,
                memoryService,
                attachmentService,
                profileUpdateCoordinator,
                turnService,
                titleService,
                runSupervisor,
                generationManager
        );
        IntentRouteResult routeResult = IntentRouteResult.builder()
                .intentType("知识问答")
                .intentEnum(com.smartorder.edu.agent.route.IntentType.KNOWLEDGE_QA)
                .complexity("简单")
                .runMode(AgentRunMode.REACT)
                .requiredTools(List.of())
                .confidence(1.0)
                .source(RoutingSource.KEYWORD)
                .reason("test")
                .build();
        ConversationTurnService.TurnState turnState = ConversationTurnService.TurnState.builder()
                .conversationId("conversation-1")
                .conversationPk(1L)
                .userMessageId(11L)
                .assistantMessageId(12L)
                .userSequenceNo(1)
                .assistantSequenceNo(2)
                .title("我准备考研")
                .created(false)
                .build();
        when(turnService.openTurn(any(), any(), any(), any(), any())).thenReturn(turnState);
        when(routeService.route(any(RouteContext.class))).thenReturn(routeResult);
        when(memoryService.getRecentChatMessages("conversation-1", 30)).thenReturn(List.of());
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(3);
            runnable.run();
            return null;
        }).when(runSupervisor).supervisedRun(any(), any(), any(), any(Runnable.class));
        AgentChatRequest request = new AgentChatRequest();
        request.setUserId(7L);
        request.setMessage("我准备考研，但是大二的时候没有怎么学会数据结构");
        request.setConversationId("conversation-1");
        SseEmitter emitter = mock(SseEmitter.class);

        orchestrator.handle(request, emitter);

        // 事务 A（openTurn 落用户消息 + 占位行）必须先于意图路由
        InOrder order = inOrder(turnService, routeService);
        order.verify(turnService).openTurn(eq("conversation-1"), eq(7L),
                eq("我准备考研，但是大二的时候没有怎么学会数据结构"), any(), any());
        order.verify(routeService).route(any(RouteContext.class));
        verify(reActEngine).execute(eq(request), eq(routeResult), any(), eq(emitter));

        // 首条消息（userSequenceNo=1）应触发异步标题生成
        verify(titleService).generateAsync(eq("conversation-1"),
                eq("我准备考研，但是大二的时候没有怎么学会数据结构"), eq(emitter), any());
    }
}
