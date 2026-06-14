package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.plan.PlanExecuteEngine;
import com.smartorder.edu.agent.profile.ProfileUpdateCoordinator;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.route.IntentRouteService;
import com.smartorder.edu.agent.route.RouteContext;
import com.smartorder.edu.agent.route.RoutingSource;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorImplTest {

    @Test
    void shouldSubmitProfileUpdateAfterSavingMessageAndBeforeRouting() {
        IntentRouteService routeService = mock(IntentRouteService.class);
        SelfManagedReActEngine reActEngine = mock(SelfManagedReActEngine.class);
        PlanExecuteEngine planExecuteEngine = mock(PlanExecuteEngine.class);
        MultiAgentCoordinator multiAgentCoordinator = mock(MultiAgentCoordinator.class);
        TraceEventDispatcher dispatcher = mock(TraceEventDispatcher.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ProfileUpdateCoordinator profileUpdateCoordinator = mock(ProfileUpdateCoordinator.class);
        AgentOrchestratorImpl orchestrator = new AgentOrchestratorImpl(
                routeService,
                reActEngine,
                planExecuteEngine,
                multiAgentCoordinator,
                dispatcher,
                memoryService,
                profileUpdateCoordinator
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
        when(routeService.route(any(RouteContext.class))).thenReturn(routeResult);
        AgentChatRequest request = new AgentChatRequest(
                7L,
                "我准备考研，但是大二的时候没有怎么学会数据结构",
                "conversation-1"
        );
        SseEmitter emitter = mock(SseEmitter.class);

        orchestrator.handle(request, emitter);

        InOrder order = inOrder(memoryService, profileUpdateCoordinator, routeService);
        order.verify(memoryService).saveUserMessage(
                "conversation-1", 7L, "我准备考研，但是大二的时候没有怎么学会数据结构");
        order.verify(profileUpdateCoordinator).submit(
                eq(7L),
                eq("conversation-1"),
                anyString(),
                eq("我准备考研，但是大二的时候没有怎么学会数据结构")
        );
        order.verify(routeService).route(any(RouteContext.class));
        verify(reActEngine).execute(eq(request), eq(routeResult), any(), eq(emitter));
    }
}
