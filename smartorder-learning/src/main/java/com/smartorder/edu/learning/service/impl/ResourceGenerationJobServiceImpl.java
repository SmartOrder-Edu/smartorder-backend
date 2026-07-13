package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import com.smartorder.edu.learning.mapper.ResourceGenerationJobMapper;
import com.smartorder.edu.learning.service.IResourceGenerationJobService;
import com.smartorder.edu.learning.service.IResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResourceGenerationJobServiceImpl implements IResourceGenerationJobService {

    private static final int MAX_RETRY_COUNT = 3;

    private final ResourceGenerationJobMapper resourceGenerationJobMapper;
    private final IResourceService resourceService;

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
    public ResourceGenerationJob createRetryPending(Long userId, Resource resource) {
        if (resource == null || resource.getId() == null) {
            throw new BusinessException("学习资源不存在");
        }
        if (!"failed".equals(resource.getStatus())) {
            throw new BusinessException("仅失败状态的资源支持重试生成");
        }

        ResourceGenerationJob latest = getLatestByResourceId(resource.getId());
        int retryCount = latest == null || latest.getRetryCount() == null ? 0 : latest.getRetryCount();
        if (retryCount >= MAX_RETRY_COUNT) {
            throw new BusinessException("该资源已达到最大重试次数，请调整资料或稍后再试");
        }

        ResourceGenerationJob job = new ResourceGenerationJob();
        job.setUserId(userId);
        job.setSpaceId(resource.getSpaceId());
        job.setResourceId(resource.getId());
        job.setPackageId(latest == null ? null : latest.getPackageId());
        job.setResourceType(resource.getResourceType());
        job.setTitle(resource.getTitle());
        job.setReason(latest != null && latest.getReason() != null && !latest.getReason().isBlank()
                ? latest.getReason()
                : "Resource Agent 重试生成「" + resource.getTitle() + "」");
        job.setStatus("pending");
        job.setRetryCount(retryCount);
        job.setIsDeleted(0);
        resourceGenerationJobMapper.insert(job);
        resourceService.updateStatus(resource.getId(), "generating");
        resource.setStatus("generating");
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

    @Override
    public ResourceGenerationJob getLatestByResourceId(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        return resourceGenerationJobMapper.selectLatestByResourceId(resourceId);
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
