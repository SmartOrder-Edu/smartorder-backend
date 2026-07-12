package com.smartorder.edu.learning.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.domain.vo.ResourceContentVO;
import com.smartorder.edu.learning.domain.vo.ResourceDetailVO;
import com.smartorder.edu.learning.domain.vo.ResourceVO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.IResourceContentService;
import com.smartorder.edu.learning.service.IResourceService;
import com.smartorder.edu.learning.service.ITaskService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/resources")
@Tag(name = "学习资源", description = "学习资源详情与内容预览接口")
public class ResourceController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IResourceService resourceService;
    private final IResourceContentService resourceContentService;
    private final ILearningSpaceService learningSpaceService;
    private final ITaskService taskService;

    @GetMapping("/{id}")
    @Operation(summary = "获取学习资源详情", description = "返回资源元信息与最新可预览内容")
    public Result<ResourceDetailVO> detail(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        Resource resource = resourceService.getById(id);
        if (resource == null) {
            throw new BusinessException("学习资源不存在");
        }

        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, resource.getSpaceId());
        ResourceContent content = resourceContentService.getLatestByResourceId(resource.getId());

        ResourceDetailVO detail = new ResourceDetailVO();
        detail.setResource(toResourceVO(resource));
        detail.setContent(toContentVO(content));
        detail.setSpaceName(space.getName());
        detail.setTaskTitle(resolveTaskTitle(resource));
        return Result.success(detail);
    }

    private String resolveTaskTitle(Resource resource) {
        if (resource.getTaskId() == null) {
            return null;
        }
        return taskService.getBySpaceId(resource.getSpaceId()).stream()
                .filter(task -> resource.getTaskId().equals(task.getId()))
                .map(Task::getTitle)
                .findFirst()
                .orElse(null);
    }

    private ResourceVO toResourceVO(Resource resource) {
        ResourceVO vo = new ResourceVO();
        vo.setId(resource.getId());
        vo.setSpaceId(resource.getSpaceId());
        vo.setPathNodeId(resource.getPathNodeId());
        vo.setTaskId(resource.getTaskId());
        vo.setTitle(resource.getTitle());
        vo.setDescription(resource.getDescription());
        vo.setResourceType(resource.getResourceType());
        vo.setUrl(resource.getUrl());
        vo.setFileFormat(resource.getFileFormat());
        vo.setCoverUrl(resource.getCoverUrl());
        vo.setIsAiGenerated(resource.getIsAiGenerated() != null && resource.getIsAiGenerated() == 1);
        vo.setSource(resource.getSource());
        vo.setStatus(resource.getStatus());
        vo.setMetadata(resource.getMetadata());
        enrichFromMetadata(vo, resource.getMetadata());
        return vo;
    }

    private ResourceContentVO toContentVO(ResourceContent content) {
        if (content == null) {
            return null;
        }
        ResourceContentVO vo = new ResourceContentVO();
        vo.setId(content.getId());
        vo.setResourceId(content.getResourceId());
        vo.setContentType(content.getContentType());
        vo.setContent(content.getContent());
        vo.setVersion(content.getVersion());
        vo.setStatus(content.getStatus());
        vo.setReviewResult(content.getReviewResult());
        vo.setCreateTime(content.getCreateTime());
        vo.setUpdateTime(content.getUpdateTime());
        return vo;
    }

    @SuppressWarnings("unchecked")
    private void enrichFromMetadata(ResourceVO vo, String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return;
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(metadata, Map.class);
            vo.setAgentReason(asString(map.get("agentReason")));
            vo.setKnowledgePoint(asString(map.get("knowledgePoint")));
            vo.setStudyScenario(asString(map.get("studyScenario")));
        } catch (Exception e) {
            log.debug("Failed to parse resource metadata: {}", metadata, e);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
