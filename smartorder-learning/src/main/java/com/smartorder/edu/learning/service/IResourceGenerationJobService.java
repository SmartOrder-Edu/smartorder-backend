package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;

import java.util.List;

public interface IResourceGenerationJobService {

    ResourceGenerationJob createPending(Long userId, Resource resource, String packageId, String reason);

    ResourceGenerationJob createRetryPending(Long userId, Resource resource);

    void markGenerating(Long jobId);

    void markCompleted(Long jobId);

    void markFailed(Long jobId, String errorMessage);

    List<ResourceGenerationJob> getRecentBySpaceId(Long spaceId, int limit);

    ResourceGenerationJob getLatestByResourceId(Long resourceId);
}
