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
import com.smartorder.edu.learning.service.impl.ResourceContentGenerator;
import com.smartorder.edu.learning.service.recommendation.BilibiliVideoRecommendationService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private final ResourceContentGenerator resourceContentGenerator;
    private final BilibiliVideoRecommendationService bilibiliVideoRecommendationService;

    @GetMapping("/{id}")
    @Operation(summary = "获取学习资源详情", description = "返回资源元信息与最新可预览内容")
    public Result<ResourceDetailVO> detail(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        Resource resource = resourceService.getById(id);
        if (resource == null) {
            throw new BusinessException("学习资源不存在");
        }

        return Result.success(buildDetail(userId, resource));
    }

    @GetMapping("/bilibili/cover")
    @Operation(summary = "代理 B站视频封面", description = "仅代理 hdslb.com 封面图片，避免浏览器直连 B站图片触发防盗链 403")
    public ResponseEntity<byte[]> bilibiliCover(@RequestParam String url) {
        BilibiliVideoRecommendationService.CoverImage image = bilibiliVideoRecommendationService.fetchCover(url);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(image.body());
    }

    @PostMapping("/{id}/generate")
    @Operation(summary = "生成待生成学习资源", description = "将 planned/failed 状态的资源生成可预览内容，并更新为 generated")
    public Result<ResourceDetailVO> generate(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        Resource resource = resourceService.getById(id);
        if (resource == null) {
            throw new BusinessException("学习资源不存在");
        }

        learningSpaceService.getSpaceById(userId, resource.getSpaceId());
        if (!"planned".equals(resource.getStatus()) && !"failed".equals(resource.getStatus())) {
            throw new BusinessException("当前资源状态不允许生成，仅支持 planned/failed");
        }

        resourceService.updateStatus(resource.getId(), "generating");
        try {
            ResourceContent content = resourceContentGenerator.generate(resource);
            resourceContentService.createContent(
                    resource.getId(),
                    content.getContentType(),
                    content.getContent(),
                    content.getReviewResult()
            );
            resourceService.markGenerated(resource.getId(), resourceContentGenerator.fileFormat(resource.getResourceType()));
            Resource generated = resourceService.getById(resource.getId());
            return Result.success(buildDetail(userId, generated));
        } catch (BusinessException e) {
            resourceService.updateStatus(resource.getId(), "failed");
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate resource {}", resource.getId(), e);
            resourceService.updateStatus(resource.getId(), "failed");
            throw new BusinessException("学习资源生成失败，请稍后重试");
        }
    }

    private ResourceDetailVO buildDetail(Long userId, Resource resource) {
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, resource.getSpaceId());
        ResourceContent content = resourceContentService.getLatestByResourceId(resource.getId());

        ResourceDetailVO detail = new ResourceDetailVO();
        detail.setResource(toResourceVO(resource));
        detail.setContent(toContentVO(content));
        detail.setSpaceName(space.getName());
        detail.setTaskTitle(resolveTaskTitle(resource));
        return detail;
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
