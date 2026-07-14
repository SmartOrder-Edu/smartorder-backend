package com.smartorder.edu.agent.route;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentRouteServiceTest {

    private StateRuleRouter stateRuleRouter;
    private LlmIntentClassifier llmIntentClassifier;
    private SlotValidator slotValidator;
    private ClarificationRouter clarificationRouter;
    private IntentRouteService service;

    @BeforeEach
    void setUp() {
        stateRuleRouter = mock(StateRuleRouter.class);
        llmIntentClassifier = mock(LlmIntentClassifier.class);
        slotValidator = mock(SlotValidator.class);
        clarificationRouter = mock(ClarificationRouter.class);
        service = new IntentRouteService(stateRuleRouter, llmIntentClassifier, slotValidator, clarificationRouter);

        when(stateRuleRouter.tryRoute(any(RouteContext.class))).thenReturn(null);
        when(slotValidator.validate(any(IntentType.class), any(RouteContext.class))).thenReturn(List.of());
    }

    @Test
    void shouldRouteMermaidTechnicalDiagramToReact() {
        IntentRouteResult result = service.route("给我关于登录实现全流程的技术图mermaid");

        assertEquals(IntentType.DIAGRAM_GENERATION, result.getIntentEnum());
        assertEquals(AgentRunMode.REACT, result.getRunMode());
        assertEquals(RoutingSource.KEYWORD, result.getSource());
        assertEquals(List.of("askUser"), result.getRequiredTools());
        verifyNoInteractions(llmIntentClassifier);
    }

    @Test
    void shouldKeepExplicitLearningResourceGenerationAsPlanAndExecute() {
        IntentRouteResult result = service.route("生成线性代数复习资料和PPT");

        assertEquals(IntentType.RESOURCE_GENERATION, result.getIntentEnum());
        assertEquals(AgentRunMode.PLAN_AND_EXECUTE, result.getRunMode());
        assertEquals(RoutingSource.KEYWORD, result.getSource());
        assertTrue(result.isNeedsPlanning());
        verifyNoInteractions(llmIntentClassifier);
    }
}
