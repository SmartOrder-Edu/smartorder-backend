package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

@Slf4j
@Service
public class ResourceAsyncGenerationService {

    private final ExecutorService executor;
    private final ResourceGenerationService resourceGenerationService;
    private final IResourceGenerationJobService jobService;

    public ResourceAsyncGenerationService(
            @Qualifier("learningResourceGenerationExecutor") ExecutorService executor,
            ResourceGenerationService resourceGenerationService,
            IResourceGenerationJobService jobService
    ) {
        this.executor = executor;
        this.resourceGenerationService = resourceGenerationService;
        this.jobService = jobService;
    }

    public void submit(ResourceGenerationJob job) {
        if (job == null || job.getId() == null) {
            log.warn("[ResourceAsyncGeneration] ignore invalid generation job: {}", job);
            return;
        }
        log.info("[ResourceAsyncGeneration] submit job={} resource={} space={} type={} title={}",
                job.getId(), job.getResourceId(), job.getSpaceId(), job.getResourceType(), job.getTitle());
        executor.execute(() -> run(job));
    }

    private void run(ResourceGenerationJob job) {
        log.info("[ResourceAsyncGeneration] start job={} resource={} user={} space={} type={}",
                job.getId(), job.getResourceId(), job.getUserId(), job.getSpaceId(), job.getResourceType());
        jobService.markGenerating(job.getId());
        try {
            resourceGenerationService.generateExistingInBackground(job.getUserId(), job.getResourceId());
            jobService.markCompleted(job.getId());
            log.info("[ResourceAsyncGeneration] completed job={} resource={} space={} type={}",
                    job.getId(), job.getResourceId(), job.getSpaceId(), job.getResourceType());
        } catch (Exception e) {
            jobService.markFailed(job.getId(), e.getMessage());
            log.error("[ResourceAsyncGeneration] failed job={} resource={} user={} space={} type={} title={} reason={}",
                    job.getId(), job.getResourceId(), job.getUserId(), job.getSpaceId(), job.getResourceType(),
                    job.getTitle(), e.getMessage(), e);
        }
    }
}
