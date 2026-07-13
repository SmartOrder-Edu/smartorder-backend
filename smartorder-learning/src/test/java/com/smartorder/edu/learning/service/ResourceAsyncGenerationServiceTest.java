package com.smartorder.edu.learning.service;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourceAsyncGenerationServiceTest {

    private ResourceGenerationService resourceGenerationService;
    private IResourceGenerationJobService jobService;
    private ResourceAsyncGenerationService service;

    @BeforeEach
    void setUp() {
        ExecutorService directExecutor = mock(ExecutorService.class);
        resourceGenerationService = mock(ResourceGenerationService.class);
        jobService = mock(IResourceGenerationJobService.class);
        service = new ResourceAsyncGenerationService(directExecutor, resourceGenerationService, jobService);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(directExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void submitMarksJobCompletedAfterBackgroundGeneration() {
        ResourceGenerationJob job = job(11L, 7L, 99L);

        service.submit(job);

        verify(jobService).markGenerating(11L);
        verify(resourceGenerationService).generateExistingInBackground(7L, 99L);
        verify(jobService).markCompleted(11L);
    }

    @Test
    void submitMarksJobFailedWhenGenerationThrows() {
        ResourceGenerationJob job = job(11L, 7L, 99L);
        when(resourceGenerationService.generateExistingInBackground(7L, 99L))
                .thenThrow(new BusinessException("生成失败"));

        service.submit(job);

        verify(jobService).markGenerating(11L);
        verify(jobService).markFailed(11L, "生成失败");
    }

    @Test
    void submitIgnoresJobWithoutId() {
        service.submit(new ResourceGenerationJob());

        verifyNoInteractions(resourceGenerationService);
        verifyNoInteractions(jobService);
    }

    private ResourceGenerationJob job(Long id, Long userId, Long resourceId) {
        ResourceGenerationJob job = new ResourceGenerationJob();
        job.setId(id);
        job.setUserId(userId);
        job.setResourceId(resourceId);
        return job;
    }
}
