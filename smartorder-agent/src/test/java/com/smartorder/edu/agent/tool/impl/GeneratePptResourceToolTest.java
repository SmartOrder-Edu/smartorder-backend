package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.service.ppt.PptGenerationRequest;
import com.smartorder.edu.learning.service.ppt.PptGenerationResult;
import com.smartorder.edu.learning.service.ppt.PptGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratePptResourceToolTest {

    private PptGenerationService pptGenerationService;
    private AgentResourceLogMapper resourceLogMapper;
    private GeneratePptResourceTool tool;

    @BeforeEach
    void setUp() {
        pptGenerationService = mock(PptGenerationService.class);
        resourceLogMapper = mock(AgentResourceLogMapper.class);
        tool = new GeneratePptResourceTool(pptGenerationService, resourceLogMapper);
    }

    @Test
    void executeDelegatesToPptGenerationServiceAndRecordsResourceLog() {
        when(pptGenerationService.generateFromResource(eq(7L), eq(88L), any(PptGenerationRequest.class)))
                .thenReturn(new PptGenerationResult(
                        99L,
                        "ppt-300",
                        "https://file.example/ppt-300.pptx",
                        "tpl-100",
                        "# 进程调度"
                ));
        when(resourceLogMapper.insert(any(AgentResourceLog.class))).thenAnswer(invocation -> {
            AgentResourceLog log = invocation.getArgument(0);
            log.setId(200L);
            return 1;
        });

        GeneratePptResourceTool.GeneratePptResourceRequest request =
                new GeneratePptResourceTool.GeneratePptResourceRequest();
        request.setSourceResourceId(88L);
        request.setSpaceId(10L);
        request.setTopic("进程调度");
        request.setTemplateStyle("education");
        request.setTargetSlides(8);

        ToolInvokeContext context = ToolInvokeContext.builder()
                .userId(7L)
                .traceId("trace-1")
                .currentUserMessage("把这个资源做成 PPT")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(request, context);

        ArgumentCaptor<PptGenerationRequest> requestCaptor = ArgumentCaptor.forClass(PptGenerationRequest.class);
        verify(pptGenerationService).generateFromResource(eq(7L), eq(88L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTopic()).isEqualTo("进程调度");
        assertThat(requestCaptor.getValue().getTemplateStyle()).isEqualTo("education");
        assertThat(requestCaptor.getValue().getTargetSlides()).isEqualTo(8);
        assertThat(requestCaptor.getValue().getUserIntent()).isEqualTo("把这个资源做成 PPT");

        ArgumentCaptor<AgentResourceLog> logCaptor = ArgumentCaptor.forClass(AgentResourceLog.class);
        verify(resourceLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(logCaptor.getValue().getResourceType()).isEqualTo("ppt");
        assertThat(logCaptor.getValue().getResultUrl()).isEqualTo("/resources/99");

        assertThat(result.get("resourceId")).isEqualTo(99L);
        assertThat(result.get("pptId")).isEqualTo("ppt-300");
        assertThat(result.get("url")).isEqualTo("/resources/99");
    }

    @Test
    void toolKeepsConfirmedWriteRiskLevel() {
        assertThat(tool.category()).isEqualTo(ToolCategory.RESOURCE);
        assertThat(tool.riskLevel()).isEqualTo(ToolCategory.RiskLevel.WRITE_CONFIRMED);
    }
}
