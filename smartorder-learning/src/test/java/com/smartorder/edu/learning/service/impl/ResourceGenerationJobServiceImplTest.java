package com.smartorder.edu.learning.service.impl;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import com.smartorder.edu.learning.mapper.ResourceGenerationJobMapper;
import com.smartorder.edu.learning.service.IResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceGenerationJobServiceImplTest {

    private ResourceGenerationJobMapper mapper;
    private IResourceService resourceService;
    private ResourceGenerationJobServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ResourceGenerationJobMapper.class);
        resourceService = mock(IResourceService.class);
        service = new ResourceGenerationJobServiceImpl(mapper, resourceService);
    }

    @Test
    void createGeneratePendingCreatesJobAndMarksResourceGenerating() {
        Resource resource = plannedResource();
        resource.setDescription("围绕进程调度生成一份讲义");
        when(mapper.insert(any(ResourceGenerationJob.class))).thenAnswer(invocation -> {
            ResourceGenerationJob job = invocation.getArgument(0, ResourceGenerationJob.class);
            job.setId(301L);
            return 1;
        });

        ResourceGenerationJob job = service.createGeneratePending(7L, resource);

        ArgumentCaptor<ResourceGenerationJob> captor = ArgumentCaptor.forClass(ResourceGenerationJob.class);
        verify(mapper).insert(captor.capture());
        ResourceGenerationJob inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getSpaceId()).isEqualTo(10L);
        assertThat(inserted.getResourceId()).isEqualTo(99L);
        assertThat(inserted.getResourceType()).isEqualTo("doc");
        assertThat(inserted.getTitle()).isEqualTo("讲义文档：进程调度");
        assertThat(inserted.getReason()).isEqualTo("围绕进程调度生成一份讲义");
        assertThat(inserted.getStatus()).isEqualTo("pending");
        assertThat(inserted.getRetryCount()).isZero();
        assertThat(job.getId()).isEqualTo(301L);
        assertThat(resource.getStatus()).isEqualTo("generating");
        verify(resourceService).updateStatus(99L, "generating");
    }

    @Test
    void createGeneratePendingReturnsLatestJobWhenResourceIsGenerating() {
        Resource resource = plannedResource();
        resource.setStatus("generating");
        ResourceGenerationJob latest = latestJob(0);
        when(mapper.selectLatestByResourceId(99L)).thenReturn(latest);

        ResourceGenerationJob job = service.createGeneratePending(7L, resource);

        assertThat(job).isSameAs(latest);
        verify(mapper, never()).insert(any(ResourceGenerationJob.class));
        verify(resourceService, never()).updateStatus(any(), any());
    }

    @Test
    void createGeneratePendingRejectsGeneratedResource() {
        Resource resource = plannedResource();
        resource.setStatus("generated");

        assertThatThrownBy(() -> service.createGeneratePending(7L, resource))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前资源状态不允许生成");
        verify(mapper, never()).insert(any(ResourceGenerationJob.class));
        verify(resourceService, never()).updateStatus(any(), any());
    }

    @Test
    void createRetryPendingCreatesJobAndMarksResourceGenerating() {
        Resource resource = failedResource();
        ResourceGenerationJob latest = latestJob(2);
        when(mapper.selectLatestByResourceId(99L)).thenReturn(latest);
        when(mapper.insert(any(ResourceGenerationJob.class))).thenAnswer(invocation -> {
            ResourceGenerationJob job = invocation.getArgument(0, ResourceGenerationJob.class);
            job.setId(300L);
            return 1;
        });

        ResourceGenerationJob job = service.createRetryPending(7L, resource);

        ArgumentCaptor<ResourceGenerationJob> captor = ArgumentCaptor.forClass(ResourceGenerationJob.class);
        verify(mapper).insert(captor.capture());
        ResourceGenerationJob inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getSpaceId()).isEqualTo(10L);
        assertThat(inserted.getResourceId()).isEqualTo(99L);
        assertThat(inserted.getPackageId()).isEqualTo("pkg-1");
        assertThat(inserted.getStatus()).isEqualTo("pending");
        assertThat(inserted.getRetryCount()).isEqualTo(2);
        assertThat(job.getId()).isEqualTo(300L);
        assertThat(resource.getStatus()).isEqualTo("generating");
        verify(resourceService).updateStatus(99L, "generating");
    }

    @Test
    void createRetryPendingRejectsNonFailedResource() {
        Resource resource = failedResource();
        resource.setStatus("generated");

        assertThatThrownBy(() -> service.createRetryPending(7L, resource))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅失败状态");
        verify(mapper, never()).insert(any(ResourceGenerationJob.class));
        verify(resourceService, never()).updateStatus(any(), any());
    }

    @Test
    void createRetryPendingRejectsResourceOverRetryLimit() {
        Resource resource = failedResource();
        when(mapper.selectLatestByResourceId(99L)).thenReturn(latestJob(3));

        assertThatThrownBy(() -> service.createRetryPending(7L, resource))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大重试次数");
        verify(mapper, never()).insert(any(ResourceGenerationJob.class));
        verify(resourceService, never()).updateStatus(any(), any());
    }

    private Resource plannedResource() {
        Resource resource = new Resource();
        resource.setId(99L);
        resource.setSpaceId(10L);
        resource.setResourceType("doc");
        resource.setTitle("讲义文档：进程调度");
        resource.setStatus("planned");
        return resource;
    }

    private Resource failedResource() {
        Resource resource = new Resource();
        resource.setId(99L);
        resource.setSpaceId(10L);
        resource.setResourceType("doc");
        resource.setTitle("讲义文档：进程调度");
        resource.setStatus("failed");
        return resource;
    }

    private ResourceGenerationJob latestJob(int retryCount) {
        ResourceGenerationJob job = new ResourceGenerationJob();
        job.setId(200L);
        job.setPackageId("pkg-1");
        job.setReason("围绕当前任务生成资料");
        job.setRetryCount(retryCount);
        return job;
    }
}
