package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import com.smartorder.edu.learning.mapper.ResourceGenerationJobMapper;
import com.smartorder.edu.learning.service.IResourceGenerationJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResourceGenerationJobServiceImpl implements IResourceGenerationJobService {

    private final ResourceGenerationJobMapper resourceGenerationJobMapper;

    @Override
    public ResourceGenerationJob createPending(Long userId, Resource resource, String packageId, String reason) {
        ResourceGenerationJob job = new ResourceGenerationJob();
        job.setUserId(userId);
        job.setSpaceId(resource.getSpaceId());
        job.setResourceId(resource.getId());
        job.setPackageId(packageId);
        job.setResourceType(resource.getResourceType());
        job.setTitle(resource.getTitle());
        job.setReason(reason);
        job.setStatus("pending");
        job.setRetryCount(0);
        job.setIsDeleted(0);
        resourceGenerationJobMapper.insert(job);
        return job;
    }

    @Override
    public void markGenerating(Long jobId) {
        updateStatus(jobId, "generating", null, false);
    }

    @Override
    public void markCompleted(Long jobId) {
        updateStatus(jobId, "generated", null, false);
    }

    @Override
    public void markFailed(Long jobId, String errorMessage) {
        updateStatus(jobId, "failed", errorMessage, true);
    }

    @Override
    public List<ResourceGenerationJob> getRecentBySpaceId(Long spaceId, int limit) {
        if (spaceId == null) {
            return List.of();
        }
        return resourceGenerationJobMapper.selectRecentBySpaceId(spaceId, Math.max(1, Math.min(limit, 20)));
    }

    private void updateStatus(Long jobId, String status, String errorMessage, boolean increaseRetry) {
        UpdateWrapper<ResourceGenerationJob> uw = new UpdateWrapper<>();
        uw.eq("id", jobId).eq("is_deleted", 0)
                .set("status", status);
        if (errorMessage != null) {
            uw.set("error_message", truncate(errorMessage));
        }
        if (increaseRetry) {
            uw.setSql("retry_count = retry_count + 1");
        }
        resourceGenerationJobMapper.update(null, uw);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
