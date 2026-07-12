package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.mapper.ResourceContentMapper;
import com.smartorder.edu.learning.service.IResourceContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceContentServiceImpl implements IResourceContentService {

    private final ResourceContentMapper resourceContentMapper;

    @Override
    public ResourceContent getLatestByResourceId(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        QueryWrapper<ResourceContent> qw = new QueryWrapper<>();
        qw.eq("resource_id", resourceId)
                .eq("status", "active")
                .eq("is_deleted", 0)
                .orderByDesc("version")
                .orderByDesc("create_time")
                .last("LIMIT 1");
        return resourceContentMapper.selectOne(qw);
    }

    @Override
    public ResourceContent createContent(Long resourceId, String contentType, String content, String reviewResult) {
        ResourceContent resourceContent = new ResourceContent();
        resourceContent.setResourceId(resourceId);
        resourceContent.setContentType(contentType);
        resourceContent.setContent(content == null ? "" : content);
        resourceContent.setVersion(nextVersion(resourceId));
        resourceContent.setStatus("active");
        resourceContent.setReviewResult(reviewResult);
        resourceContent.setIsDeleted(0);
        resourceContentMapper.insert(resourceContent);
        return resourceContent;
    }

    @Override
    public List<ResourceContent> batchCreate(List<ResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        for (ResourceContent content : contents) {
            if (content.getVersion() == null) {
                content.setVersion(nextVersion(content.getResourceId()));
            }
            if (content.getStatus() == null) {
                content.setStatus("active");
            }
            if (content.getIsDeleted() == null) {
                content.setIsDeleted(0);
            }
            if (content.getContent() == null) {
                content.setContent("");
            }
            resourceContentMapper.insert(content);
        }
        log.info("[ResourceContent] batchCreate: 已插入 {} 条资源内容", contents.size());
        return contents;
    }

    private Integer nextVersion(Long resourceId) {
        QueryWrapper<ResourceContent> qw = new QueryWrapper<>();
        qw.select("MAX(version) AS version")
                .eq("resource_id", resourceId)
                .eq("is_deleted", 0);
        ResourceContent latest = resourceContentMapper.selectOne(qw);
        Integer maxVersion = latest == null ? null : latest.getVersion();
        return maxVersion == null ? 1 : maxVersion + 1;
    }
}
