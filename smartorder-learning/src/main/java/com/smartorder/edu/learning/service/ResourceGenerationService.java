package com.smartorder.edu.learning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.dto.ResourceGenerationRequest;
import com.smartorder.edu.learning.service.impl.ResourceContentGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceGenerationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_RESOURCE_TYPES = Set.of(
            "ppt", "doc", "html", "video", "mind_map", "quiz",
            "case_study", "flashcard_set", "image", "audio", "link"
    );

    private final ILearningSpaceService learningSpaceService;
    private final ITaskService taskService;
    private final ILearningPathNodeService pathNodeService;
    private final IResourceService resourceService;
    private final IResourceContentService resourceContentService;
    private final ResourceContentGenerator resourceContentGenerator;

    public ResourceGenerationResult generateNew(Long userId, ResourceGenerationRequest request) {
        ResourceGenerationRequest safeRequest = request == null ? new ResourceGenerationRequest() : request;
        if (safeRequest.getSpaceId() == null) {
            throw new BusinessException("学习空间ID不能为空");
        }

        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, safeRequest.getSpaceId());
        Task task = resolveTask(safeRequest.getSpaceId(), safeRequest.getTaskId());
        LearningPathNode pathNode = resolvePathNode(safeRequest.getSpaceId(), safeRequest.getPathNodeId(), task);
        String resourceType = normalizeResourceType(safeRequest.getResourceType());
        String knowledgePoint = resolveKnowledgePoint(space, task, pathNode, safeRequest.getTopic());
        String generationId = UUID.randomUUID().toString();
        String reason = buildReason(task, knowledgePoint, safeRequest);

        Resource resource = new Resource();
        resource.setSpaceId(safeRequest.getSpaceId());
        resource.setTaskId(task != null ? task.getId() : safeRequest.getTaskId());
        resource.setPathNodeId(pathNode != null ? pathNode.getId() : safeRequest.getPathNodeId());
        resource.setTitle(resourceTitle(resourceType, knowledgePoint));
        resource.setDescription(resourceDescription(resourceType, knowledgePoint, safeRequest));
        resource.setResourceType(resourceType);
        resource.setUrl(resourceUrl(resourceType, knowledgePoint, generationId));
        resource.setFileFormat(resourceContentGenerator.fileFormat(resourceType));
        resource.setIsAiGenerated("link".equals(resourceType) ? 0 : 1);
        resource.setSource("link".equals(resourceType) ? "curated" : "ai");
        resource.setStatus("generated");
        resource.setMetadata(toJson(metadata(generationId, knowledgePoint, reason, safeRequest, resourceType)));
        resource.setIsDeleted(0);

        Resource inserted = resourceService.batchInsertGenerated(List.of(resource)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException("资源记录创建失败"));
        ResourceContent content = createContent(inserted, safeRequest);
        log.info("[ResourceGeneration] generated resource id={} space={} type={} topic={}",
                inserted.getId(), inserted.getSpaceId(), inserted.getResourceType(), knowledgePoint);
        return new ResourceGenerationResult(inserted, content, space, task);
    }

    public ResourceGenerationResult generateExisting(Long userId, Long resourceId) {
        Resource resource = resourceService.getById(resourceId);
        if (resource == null) {
            throw new BusinessException("学习资源不存在");
        }
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, resource.getSpaceId());
        if (!"planned".equals(resource.getStatus()) && !"failed".equals(resource.getStatus())) {
            throw new BusinessException("当前资源状态不允许生成，仅支持 planned/failed");
        }

        resourceService.updateStatus(resource.getId(), "generating");
        try {
            ResourceContent generated = resourceContentGenerator.generate(resource);
            ResourceContent content = resourceContentService.createContent(
                    resource.getId(),
                    generated.getContentType(),
                    generated.getContent(),
                    generated.getReviewResult()
            );
            resourceService.markGenerated(resource.getId(), resourceContentGenerator.fileFormat(resource.getResourceType()));
            Resource updated = resourceService.getById(resource.getId());
            Task task = resolveTask(updated.getSpaceId(), updated.getTaskId());
            return new ResourceGenerationResult(updated, content, space, task);
        } catch (BusinessException e) {
            resourceService.updateStatus(resource.getId(), "failed");
            throw e;
        } catch (Exception e) {
            log.error("[ResourceGeneration] failed to generate existing resource {}", resource.getId(), e);
            resourceService.updateStatus(resource.getId(), "failed");
            throw new BusinessException("学习资源生成失败，请稍后重试");
        }
    }

    private ResourceContent createContent(Resource resource, ResourceGenerationRequest request) {
        if (request.getGeneratedContent() != null && !request.getGeneratedContent().isBlank()) {
            return resourceContentService.createContent(
                    resource.getId(),
                    normalizeContentType(request.getGeneratedContentType()),
                    request.getGeneratedContent(),
                    reviewResult("passed", List.of("non_empty", "bound_to_space", "llm_generated"))
            );
        }
        ResourceContent generated = resourceContentGenerator.generate(resource);
        return resourceContentService.createContent(
                resource.getId(),
                generated.getContentType(),
                generated.getContent(),
                generated.getReviewResult()
        );
    }

    private Task resolveTask(Long spaceId, Long taskId) {
        if (taskId == null) {
            return null;
        }
        return taskService.getBySpaceId(spaceId).stream()
                .filter(task -> taskId.equals(task.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("任务不存在或不属于该学习空间"));
    }

    private LearningPathNode resolvePathNode(Long spaceId, Long requestedPathNodeId, Task task) {
        Long pathNodeId = requestedPathNodeId != null ? requestedPathNodeId : (task == null ? null : task.getPathNodeId());
        if (pathNodeId == null) {
            return null;
        }
        return pathNodeService.getBySpaceId(spaceId).stream()
                .filter(node -> pathNodeId.equals(node.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("学习路径节点不存在或不属于该学习空间"));
    }

    private String normalizeResourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "doc";
        }
        String type = raw.trim().toLowerCase()
                .replace("-", "_")
                .replace("mindmap", "mind_map")
                .replace("mind_map_map", "mind_map");
        if ("markdown".equals(type) || "note".equals(type) || "notes".equals(type)) {
            type = "doc";
        }
        if ("exercise".equals(type) || "question".equals(type) || "questions".equals(type)) {
            type = "quiz";
        }
        if (!ALLOWED_RESOURCE_TYPES.contains(type)) {
            throw new BusinessException("不支持的资源类型：" + raw);
        }
        return type;
    }

    private String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "markdown";
        }
        String type = raw.trim().toLowerCase();
        return switch (type) {
            case "markdown", "html", "json", "external_link" -> type;
            default -> "markdown";
        };
    }

    private String resolveKnowledgePoint(LearningSpaceVO space, Task task, LearningPathNode pathNode, String topic) {
        if (topic != null && !topic.isBlank()) {
            return topic.trim();
        }
        if (task != null && task.getTitle() != null && !task.getTitle().isBlank()) {
            return task.getTitle().trim();
        }
        if (pathNode != null && pathNode.getTitle() != null && !pathNode.getTitle().isBlank()) {
            return pathNode.getTitle().trim();
        }
        if (space.getName() != null && !space.getName().isBlank()) {
            return space.getName();
        }
        return "当前知识点";
    }

    private String buildReason(Task task, String knowledgePoint, ResourceGenerationRequest request) {
        String base = task != null
                ? "Resource Agent 围绕当前任务「" + task.getTitle() + "」生成可直接学习的资源"
                : "Resource Agent 围绕「" + knowledgePoint + "」生成可直接学习的资源";
        if (request.getUserIntent() != null && !request.getUserIntent().isBlank()) {
            return base + "，并响应需求：" + request.getUserIntent().trim();
        }
        return base + "。";
    }

    private String resourceTitle(String type, String knowledgePoint) {
        return switch (type) {
            case "ppt" -> "演示讲义：" + knowledgePoint;
            case "doc" -> "讲义文档：" + knowledgePoint;
            case "html" -> "交互知识页：" + knowledgePoint;
            case "mind_map" -> "思维导图：" + knowledgePoint;
            case "quiz" -> "专项练习：" + knowledgePoint;
            case "case_study" -> "案例分析：" + knowledgePoint;
            case "flashcard_set" -> "记忆卡片：" + knowledgePoint;
            case "image" -> "图解卡片：" + knowledgePoint;
            case "audio" -> "音频讲稿：" + knowledgePoint;
            case "video" -> "短视频脚本：" + knowledgePoint;
            case "link" -> "外部资源推荐：" + knowledgePoint;
            default -> "学习资料：" + knowledgePoint;
        };
    }

    private String resourceDescription(String type, String knowledgePoint, ResourceGenerationRequest request) {
        int minutes = request.getTargetMinutes() == null || request.getTargetMinutes() <= 0
                ? 30
                : request.getTargetMinutes();
        return switch (type) {
            case "mind_map" -> "梳理「" + knowledgePoint + "」的概念层级、关联知识和复盘路径。";
            case "quiz" -> "围绕「" + knowledgePoint + "」生成即时巩固练习，用于学习后检测。";
            case "html" -> "以结构化页面组织知识点，适合边看边操作。";
            case "image" -> "把抽象概念压缩成一张可复盘的图解卡片。";
            case "video" -> "生成可用于后续视频合成的讲解脚本和分镜。";
            case "link" -> "为当前知识点推荐外部学习入口。";
            default -> "按 " + minutes + " 分钟学习节奏整理核心概念、例题和复盘要点。";
        };
    }

    private String resourceUrl(String type, String knowledgePoint, String generationId) {
        String encoded = URLEncoder.encode(knowledgePoint, StandardCharsets.UTF_8);
        if ("link".equals(type)) {
            return "https://search.bilibili.com/all?keyword=" + encoded;
        }
        return "/learning-resources/generated/" + generationId + "/" + type;
    }

    private Map<String, Object> metadata(
            String generationId,
            String knowledgePoint,
            String reason,
            ResourceGenerationRequest request,
            String resourceType
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("generationId", generationId);
        map.put("knowledgePoint", knowledgePoint);
        map.put("agentReason", reason);
        map.put("studyScenario", studyScenario(resourceType));
        map.put("packageMode", request.getPackageMode());
        map.put("targetMinutes", request.getTargetMinutes());
        map.put("userIntent", request.getUserIntent());
        map.put("phaseIndex", request.getPhaseIndex());
        map.put("generatedBy", "resource_generation_service");
        return map;
    }

    private String studyScenario(String type) {
        return switch (type) {
            case "doc", "html", "ppt" -> "学习";
            case "mind_map", "flashcard_set" -> "复盘";
            case "quiz" -> "测评";
            case "image" -> "理解";
            case "video", "audio", "link" -> "补充讲解";
            case "case_study" -> "应用";
            default -> "学习";
        };
    }

    private String reviewResult(String status, List<String> checks) {
        return toJson(Map.of(
                "status", status,
                "checks", checks,
                "summary", "资源内容已通过基础格式校验，后续可接入 Supervisor Agent 深度审核"
        ));
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("资源生成数据序列化失败");
        }
    }
}
