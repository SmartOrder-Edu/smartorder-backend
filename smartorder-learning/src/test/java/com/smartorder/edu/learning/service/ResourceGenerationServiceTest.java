package com.smartorder.edu.learning.service;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.dto.ResourceGenerationRequest;
import com.smartorder.edu.learning.service.impl.ResourceContentGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceGenerationServiceTest {

    private ILearningSpaceService learningSpaceService;
    private ITaskService taskService;
    private ILearningPathNodeService pathNodeService;
    private IResourceService resourceService;
    private IResourceContentService resourceContentService;
    private ResourceContentGenerator resourceContentGenerator;
    private ResourceGenerationService service;

    @BeforeEach
    void setUp() {
        learningSpaceService = mock(ILearningSpaceService.class);
        taskService = mock(ITaskService.class);
        pathNodeService = mock(ILearningPathNodeService.class);
        resourceService = mock(IResourceService.class);
        resourceContentService = mock(IResourceContentService.class);
        resourceContentGenerator = mock(ResourceContentGenerator.class);
        service = new ResourceGenerationService(
                learningSpaceService,
                taskService,
                pathNodeService,
                resourceService,
                resourceContentService,
                resourceContentGenerator
        );
    }

    @Test
    void generateNewPersistsResourceAndContentInLearningTables() {
        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        space.setName("操作系统复习");
        when(learningSpaceService.getSpaceById(7L, 10L)).thenReturn(space);

        Task task = new Task();
        task.setId(20L);
        task.setSpaceId(10L);
        task.setPathNodeId(30L);
        task.setTitle("进程调度算法");
        when(taskService.getBySpaceId(10L)).thenReturn(List.of(task));

        LearningPathNode node = new LearningPathNode();
        node.setId(30L);
        node.setSpaceId(10L);
        node.setTitle("进程管理");
        when(pathNodeService.getBySpaceId(10L)).thenReturn(List.of(node));
        when(resourceContentGenerator.fileFormat("doc")).thenReturn("md");
        when(resourceService.batchInsertGenerated(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Resource> resources = invocation.getArgument(0, List.class);
            resources.getFirst().setId(99L);
            return resources;
        });

        ResourceContent content = new ResourceContent();
        content.setId(100L);
        content.setResourceId(99L);
        content.setContentType("markdown");
        content.setContent("# 进程调度算法");
        when(resourceContentService.createContent(eq(99L), eq("markdown"), eq("# 进程调度算法"), any()))
                .thenReturn(content);

        ResourceGenerationRequest request = new ResourceGenerationRequest();
        request.setSpaceId(10L);
        request.setTaskId(20L);
        request.setResourceType("DOC");
        request.setTopic("进程调度算法");
        request.setGeneratedContent("# 进程调度算法");
        request.setGeneratedContentType("markdown");

        ResourceGenerationResult result = service.generateNew(7L, request);

        ArgumentCaptor<List<Resource>> captor = ArgumentCaptor.forClass(List.class);
        verify(resourceService).batchInsertGenerated(captor.capture());
        Resource saved = captor.getValue().getFirst();
        assertThat(saved.getSpaceId()).isEqualTo(10L);
        assertThat(saved.getTaskId()).isEqualTo(20L);
        assertThat(saved.getPathNodeId()).isEqualTo(30L);
        assertThat(saved.getResourceType()).isEqualTo("doc");
        assertThat(saved.getStatus()).isEqualTo("generated");
        assertThat(saved.getMetadata()).contains("进程调度算法", "resource_generation_service");
        assertThat(result.resource().getId()).isEqualTo(99L);
        assertThat(result.content().getId()).isEqualTo(100L);
    }

    @Test
    void generateNewRejectsTaskOutsideSpaceBeforeWriting() {
        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        space.setName("高数");
        when(learningSpaceService.getSpaceById(7L, 10L)).thenReturn(space);
        when(taskService.getBySpaceId(10L)).thenReturn(List.of());

        ResourceGenerationRequest request = new ResourceGenerationRequest();
        request.setSpaceId(10L);
        request.setTaskId(999L);
        request.setTopic("极限");

        assertThatThrownBy(() -> service.generateNew(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");
        verify(resourceService, never()).batchInsertGenerated(any());
        verify(resourceContentService, never()).createContent(any(), any(), any(), any());
    }

    @Test
    void generateExistingInBackgroundAllowsGeneratingResource() {
        Resource resource = new Resource();
        resource.setId(99L);
        resource.setSpaceId(10L);
        resource.setTaskId(20L);
        resource.setResourceType("doc");
        resource.setStatus("generating");
        when(resourceService.getById(99L)).thenReturn(resource);

        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        when(learningSpaceService.getSpaceById(7L, 10L)).thenReturn(space);

        Task task = new Task();
        task.setId(20L);
        task.setSpaceId(10L);
        when(taskService.getBySpaceId(10L)).thenReturn(List.of(task));

        ResourceContent generated = new ResourceContent();
        generated.setContentType("markdown");
        generated.setContent("# 已生成内容");
        generated.setReviewResult("{\"status\":\"passed\"}");
        when(resourceContentGenerator.generate(resource, 7L)).thenReturn(generated);

        ResourceContent content = new ResourceContent();
        content.setId(100L);
        content.setResourceId(99L);
        when(resourceContentService.createContent(99L, "markdown", "# 已生成内容", "{\"status\":\"passed\"}"))
                .thenReturn(content);
        when(resourceContentGenerator.fileFormat("doc")).thenReturn("md");
        when(resourceService.getById(99L)).thenReturn(resource);

        ResourceGenerationResult result = service.generateExistingInBackground(7L, 99L);

        assertThat(result.resource()).isSameAs(resource);
        assertThat(result.content()).isSameAs(content);
        verify(resourceService, never()).updateStatus(99L, "generating");
        verify(resourceService).markGenerated(99L, "md");
    }
}
