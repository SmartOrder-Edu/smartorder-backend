package com.smartorder.edu.learning.service.impl;

import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.domain.vo.ResourcePackageVO;
import com.smartorder.edu.learning.dto.ResourcePackageGenerateDTO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.IResourceGenerationJobService;
import com.smartorder.edu.learning.service.IResourceService;
import com.smartorder.edu.learning.service.ITaskService;
import com.smartorder.edu.learning.service.ResourceAsyncGenerationService;
import com.smartorder.edu.learning.service.ResourceStreamingGenerationService;
import com.smartorder.edu.learning.service.recommendation.BilibiliVideoRecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourcePackageServiceImplTest {

    private ILearningSpaceService learningSpaceService;
    private ITaskService taskService;
    private IResourceService resourceService;
    private IResourceGenerationJobService resourceGenerationJobService;
    private ResourceAsyncGenerationService resourceAsyncGenerationService;
    private ResourceStreamingGenerationService resourceStreamingGenerationService;
    private ExecutorService resourceGenerationExecutor;
    private ResourcePackageServiceImpl service;

    @BeforeEach
    void setUp() {
        learningSpaceService = mock(ILearningSpaceService.class);
        taskService = mock(ITaskService.class);
        resourceService = mock(IResourceService.class);
        resourceGenerationJobService = mock(IResourceGenerationJobService.class);
        resourceAsyncGenerationService = mock(ResourceAsyncGenerationService.class);
        resourceStreamingGenerationService = mock(ResourceStreamingGenerationService.class);
        resourceGenerationExecutor = mock(ExecutorService.class);
        BilibiliVideoRecommendationService bilibiliVideoRecommendationService =
                mock(BilibiliVideoRecommendationService.class);
        service = new ResourcePackageServiceImpl(
                learningSpaceService,
                taskService,
                resourceService,
                resourceGenerationJobService,
                resourceAsyncGenerationService,
                bilibiliVideoRecommendationService,
                resourceStreamingGenerationService,
                resourceGenerationExecutor
        );
    }

    @Test
    void generatePackagePersistsPlannedResourcesAndSubmitsBackgroundJobs() {
        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        space.setName("操作系统");
        when(learningSpaceService.getSpaceById(7L, 10L)).thenReturn(space);

        Task task = new Task();
        task.setId(20L);
        task.setSpaceId(10L);
        task.setPathNodeId(30L);
        task.setTitle("进程调度");
        task.setStatus("pending");
        when(taskService.getBySpaceId(10L)).thenReturn(List.of(task));

        List<String> statusesAtInsert = new ArrayList<>();
        when(resourceService.batchInsertPlanned(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Resource> resources = invocation.getArgument(0, List.class);
            statusesAtInsert.addAll(resources.stream().map(Resource::getStatus).toList());
            for (int i = 0; i < resources.size(); i++) {
                resources.get(i).setId(100L + i);
            }
            return resources;
        });
        when(resourceGenerationJobService.createPending(eq(7L), any(Resource.class), any(), any()))
                .thenAnswer(invocation -> {
                    Resource resource = invocation.getArgument(1, Resource.class);
                    ResourceGenerationJob job = new ResourceGenerationJob();
                    job.setId(resource.getId() + 1000);
                    job.setUserId(7L);
                    job.setSpaceId(resource.getSpaceId());
                    job.setResourceId(resource.getId());
                    return job;
                });

        ResourcePackageGenerateDTO dto = new ResourcePackageGenerateDTO();
        dto.setResourceTypes(List.of("doc", "quiz"));
        dto.setUserIntent("先给我可练习的材料");

        ResourcePackageVO result = service.generatePackage(7L, 10L, dto);

        assertThat(result.getStatus()).isEqualTo("generating");
        assertThat(result.getResources()).hasSize(2);
        assertThat(result.getResources()).allSatisfy(resource -> assertThat(resource.getStatus()).isEqualTo("generating"));
        assertThat(result.getAgentActions()).extracting("status")
                .containsExactly("success", "running", "running");

        ArgumentCaptor<List<Resource>> resourcesCaptor = ArgumentCaptor.forClass(List.class);
        verify(resourceService).batchInsertPlanned(resourcesCaptor.capture());
        assertThat(statusesAtInsert).containsExactly("planned", "planned");
        verify(resourceService).updateStatus(100L, "generating");
        verify(resourceService).updateStatus(101L, "generating");
        verify(resourceGenerationJobService).createPending(eq(7L), eq(resourcesCaptor.getValue().get(0)), any(), any());
        verify(resourceGenerationJobService).createPending(eq(7L), eq(resourcesCaptor.getValue().get(1)), any(), any());
        verify(resourceAsyncGenerationService, times(2)).submit(any(ResourceGenerationJob.class));
    }
}
