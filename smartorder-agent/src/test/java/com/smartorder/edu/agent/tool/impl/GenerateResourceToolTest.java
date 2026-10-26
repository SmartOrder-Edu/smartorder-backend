package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.util.PromptLoader;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.dto.ResourceGenerationRequest;
import com.smartorder.edu.learning.service.ResourceGenerationResult;
import com.smartorder.edu.learning.service.ResourceGenerationService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateResourceToolTest {

    private ChatModel chatModel;
    private AgentResourceLogMapper resourceLogMapper;
    private PromptLoader promptLoader;
    private ResourceGenerationService resourceGenerationService;
    private GenerateResourceTool tool;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        resourceLogMapper = mock(AgentResourceLogMapper.class);
        promptLoader = mock(PromptLoader.class);
        resourceGenerationService = mock(ResourceGenerationService.class);
        tool = new GenerateResourceTool(chatModel, resourceLogMapper, promptLoader, resourceGenerationService);
    }

    @Test
    void executePersistsLlmOutputAsOfficialLearningResource() {
        when(promptLoader.getResourceGenTemplate()).thenReturn("请生成{0}:{1}");
        when(chatModel.chat(anyString())).thenReturn("# 进程调度算法");

        Resource resource = new Resource();
        resource.setId(99L);
        resource.setTitle("讲义文档：进程调度算法");
        resource.setResourceType("doc");
        when(resourceGenerationService.generateNew(any(), any(ResourceGenerationRequest.class)))
                .thenReturn(new ResourceGenerationResult(resource, null, null, null));
        when(resourceLogMapper.insert(any(AgentResourceLog.class))).thenAnswer(invocation -> {
            AgentResourceLog log = invocation.getArgument(0);
            log.setId(200L);
            return 1;
        });

        GenerateResourceTool.GenerateResourceRequest request = new GenerateResourceTool.GenerateResourceRequest();
        request.setSpaceId(10L);
        request.setTopic("进程调度算法");
        request.setResourceType("DOC");

        ToolInvokeContext context = ToolInvokeContext.builder()
                .userId(7L)
                .traceId("trace-1")
                .currentUserMessage("生成一份进程调度复习资料")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(request, context);

        ArgumentCaptor<ResourceGenerationRequest> generationCaptor = ArgumentCaptor.forClass(ResourceGenerationRequest.class);
        verify(resourceGenerationService).generateNew(eq(7L), generationCaptor.capture());
        ResourceGenerationRequest generationRequest = generationCaptor.getValue();
        assertThat(generationRequest.getSpaceId()).isEqualTo(10L);
        assertThat(generationRequest.getTopic()).isEqualTo("进程调度算法");
        assertThat(generationRequest.getGeneratedContent()).isEqualTo("# 进程调度算法");
        assertThat(generationRequest.getGeneratedContentType()).isEqualTo("markdown");

        ArgumentCaptor<AgentResourceLog> logCaptor = ArgumentCaptor.forClass(AgentResourceLog.class);
        verify(resourceLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(logCaptor.getValue().getResultUrl()).isEqualTo("/resources/99");

        assertThat(result.get("resourceId")).isEqualTo(99L);
        assertThat(result.get("resourceLogId")).isEqualTo(200L);
        assertThat(result.get("url")).isEqualTo("/resources/99");
    }
}
