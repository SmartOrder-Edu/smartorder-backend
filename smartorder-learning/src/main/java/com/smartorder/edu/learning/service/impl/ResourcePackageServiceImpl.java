package com.smartorder.edu.learning.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.AgentActionVO;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.domain.vo.ResourcePackageVO;
import com.smartorder.edu.learning.domain.vo.ResourceVO;
import com.smartorder.edu.learning.dto.ResourcePackageGenerateDTO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.IResourcePackageService;
import com.smartorder.edu.learning.service.IResourceService;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourcePackageServiceImpl implements IResourcePackageService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> DEFAULT_RESOURCE_TYPES = List.of("doc", "mind_map", "quiz", "link");
    private static final List<String> ALLOWED_RESOURCE_TYPES = List.of(
            "doc", "html", "mind_map", "quiz", "image", "video", "link"
    );

    private final ILearningSpaceService learningSpaceService;
    private final ITaskService taskService;
    private final IResourceService resourceService;

    @Override
    public ResourcePackageVO previewPackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto) {
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, spaceId);
        Task task = resolveTask(spaceId, dto);
        String packageId = UUID.randomUUID().toString();
        return buildPackage(space, task, normalizeDto(dto), packageId, false);
    }

    @Override
    public ResourcePackageVO generatePackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto) {
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, spaceId);
        Task task = resolveTask(spaceId, dto);
        ResourcePackageGenerateDTO normalized = normalizeDto(dto);
        String packageId = UUID.randomUUID().toString();
        ResourcePackageVO preview = buildPackage(space, task, normalized, packageId, false);

        List<Resource> resources = preview.getResources().stream()
                .map(vo -> toEntity(vo, packageId))
                .toList();
        List<Resource> inserted = resourceService.batchInsertGenerated(resources);

        ResourcePackageVO generated = buildPackage(space, task, normalized, packageId, true);
        generated.setResources(inserted.stream().map(this::toVO).toList());
        generated.setStatus("generated");
        generated.setAgentActions(buildAgentActions(generated.getReason(), "success"));
        log.info("[ResourcePackage] user={} space={} package={} generated {} resources",
                userId, spaceId, packageId, inserted.size());
        return generated;
    }

    private ResourcePackageGenerateDTO normalizeDto(ResourcePackageGenerateDTO dto) {
        ResourcePackageGenerateDTO normalized = dto == null ? new ResourcePackageGenerateDTO() : dto;
        List<String> requested = normalized.getResourceTypes();
        if (requested == null || requested.isEmpty()) {
            normalized.setResourceTypes(DEFAULT_RESOURCE_TYPES);
        } else {
            List<String> cleanTypes = requested.stream()
                    .filter(type -> type != null && !type.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (cleanTypes.isEmpty()) {
                normalized.setResourceTypes(DEFAULT_RESOURCE_TYPES);
            } else if (!ALLOWED_RESOURCE_TYPES.containsAll(cleanTypes)) {
                throw new BusinessException("存在不支持的资源类型，允许的值：" + ALLOWED_RESOURCE_TYPES);
            } else {
                normalized.setResourceTypes(cleanTypes);
            }
        }
        if (normalized.getPackageMode() == null || normalized.getPackageMode().isBlank()) {
            normalized.setPackageMode("task");
        }
        if (normalized.getTargetMinutes() == null || normalized.getTargetMinutes() <= 0) {
            normalized.setTargetMinutes(30);
        }
        return normalized;
    }

    private Task resolveTask(Long spaceId, ResourcePackageGenerateDTO dto) {
        List<Task> tasks = taskService.getBySpaceId(spaceId);
        if (dto != null && dto.getTaskId() != null) {
            return tasks.stream()
                    .filter(task -> dto.getTaskId().equals(task.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("任务不存在或不属于该学习空间"));
        }
        return tasks.stream()
                .filter(task -> "in_progress".equals(task.getStatus()) || "pending".equals(task.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private ResourcePackageVO buildPackage(
            LearningSpaceVO space,
            Task task,
            ResourcePackageGenerateDTO dto,
            String packageId,
            boolean generated
    ) {
        String knowledgePoint = resolveKnowledgePoint(space, task, dto);
        String reason = buildReason(task, knowledgePoint, dto);
        ResourcePackageVO vo = new ResourcePackageVO();
        vo.setPackageId(packageId);
        vo.setSpaceId(space.getId());
        vo.setTaskId(task != null ? task.getId() : dto.getTaskId());
        vo.setPathNodeId(task != null && task.getPathNodeId() != null ? task.getPathNodeId() : dto.getPathNodeId());
        vo.setKnowledgePoint(knowledgePoint);
        vo.setPackageMode(dto.getPackageMode());
        vo.setTitle(buildPackageTitle(task, knowledgePoint, dto));
        vo.setReason(reason);
        vo.setStatus(generated ? "generated" : "preview");
        vo.setResources(dto.getResourceTypes().stream()
                .map(type -> buildResourceVO(space.getId(), vo.getTaskId(), vo.getPathNodeId(),
                        type, knowledgePoint, reason, dto, packageId, generated))
                .toList());
        vo.setAgentActions(buildAgentActions(reason, generated ? "success" : "waiting_user_confirm"));
        return vo;
    }

    private String resolveKnowledgePoint(LearningSpaceVO space, Task task, ResourcePackageGenerateDTO dto) {
        if (dto.getKnowledgePoint() != null && !dto.getKnowledgePoint().isBlank()) {
            return dto.getKnowledgePoint().trim();
        }
        if (task != null && task.getTitle() != null && !task.getTitle().isBlank()) {
            return task.getTitle().trim();
        }
        if (space.getGoals() != null && !space.getGoals().isBlank()) {
            return space.getName() + "核心目标";
        }
        return space.getName();
    }

    private String buildPackageTitle(Task task, String knowledgePoint, ResourcePackageGenerateDTO dto) {
        String scope = task != null ? task.getTitle() : knowledgePoint;
        return switch (dto.getPackageMode()) {
            case "weakness" -> "薄弱点强化资料包：" + scope;
            case "review" -> "复习资料包：" + scope;
            case "exam" -> "冲刺资料包：" + scope;
            default -> "今日任务资料包：" + scope;
        };
    }

    private String buildReason(Task task, String knowledgePoint, ResourcePackageGenerateDTO dto) {
        String base = task != null
                ? "围绕当前任务「" + task.getTitle() + "」补齐可直接学习的资料"
                : "围绕「" + knowledgePoint + "」补齐可直接学习的资料";
        if (dto.getUserIntent() != null && !dto.getUserIntent().isBlank()) {
            return base + "，并响应你的需求：" + dto.getUserIntent().trim();
        }
        return base + "，便于完成讲解、框架梳理、练习巩固和外部视频筛选。";
    }

    private ResourceVO buildResourceVO(
            Long spaceId,
            Long taskId,
            Long pathNodeId,
            String type,
            String knowledgePoint,
            String reason,
            ResourcePackageGenerateDTO dto,
            String packageId,
            boolean generated
    ) {
        ResourceVO vo = new ResourceVO();
        vo.setSpaceId(spaceId);
        vo.setTaskId(taskId);
        vo.setPathNodeId(pathNodeId);
        vo.setResourceType(type);
        vo.setTitle(resourceTitle(type, knowledgePoint));
        vo.setDescription(resourceDescription(type, knowledgePoint, dto));
        vo.setUrl(resourceUrl(type, knowledgePoint, packageId));
        vo.setFileFormat(fileFormat(type));
        vo.setIsAiGenerated(!"link".equals(type));
        vo.setSource("link".equals(type) ? "curated" : "ai");
        vo.setStatus(generated ? "generated" : "planned");
        vo.setAgentReason(reason);
        vo.setKnowledgePoint(knowledgePoint);
        vo.setStudyScenario(studyScenario(type));
        vo.setMetadata(toJson(metadata(packageId, knowledgePoint, reason, dto, type)));
        return vo;
    }

    private Resource toEntity(ResourceVO vo, String packageId) {
        Resource resource = new Resource();
        resource.setSpaceId(vo.getSpaceId());
        resource.setTaskId(vo.getTaskId());
        resource.setPathNodeId(vo.getPathNodeId());
        resource.setTitle(vo.getTitle());
        resource.setDescription(vo.getDescription());
        resource.setResourceType(vo.getResourceType());
        resource.setUrl(vo.getUrl());
        resource.setFileFormat(vo.getFileFormat());
        resource.setCoverUrl(vo.getCoverUrl());
        resource.setIsAiGenerated(Boolean.TRUE.equals(vo.getIsAiGenerated()) ? 1 : 0);
        resource.setSource(vo.getSource());
        resource.setStatus("generated");
        resource.setMetadata(vo.getMetadata());
        resource.setIsDeleted(0);
        if (resource.getMetadata() == null || resource.getMetadata().isBlank()) {
            resource.setMetadata(toJson(Map.of("packageId", packageId)));
        }
        return resource;
    }

    private ResourceVO toVO(Resource resource) {
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

    private List<AgentActionVO> buildAgentActions(String reason, String finalStatus) {
        List<AgentActionVO> actions = new ArrayList<>();
        actions.add(agentAction("resource", "analyze_context", "learning_space",
                "资源 Agent 分析当前空间、任务和知识点", "success"));
        actions.add(agentAction("resource", "generate_resource", "resource",
                reason, finalStatus));
        actions.add(agentAction("supervisor", "review_resource", "resource",
                "Supervisor Agent 检查资料类型、来源标注和可执行绑定关系", finalStatus));
        return actions;
    }

    private AgentActionVO agentAction(String agentType, String actionType, String targetType, String reason, String status) {
        AgentActionVO action = new AgentActionVO();
        action.setAgentType(agentType);
        action.setActionType(actionType);
        action.setTargetType(targetType);
        action.setReason(reason);
        action.setStatus(status);
        action.setCreateTime(LocalDateTime.now());
        return action;
    }

    private Map<String, Object> metadata(
            String packageId,
            String knowledgePoint,
            String reason,
            ResourcePackageGenerateDTO dto,
            String type
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("packageId", packageId);
        map.put("knowledgePoint", knowledgePoint);
        map.put("agentReason", reason);
        map.put("studyScenario", studyScenario(type));
        map.put("packageMode", dto.getPackageMode());
        map.put("targetMinutes", dto.getTargetMinutes());
        map.put("userIntent", dto.getUserIntent());
        if ("link".equals(type)) {
            map.put("recommendationSource", "bilibili");
            map.put("recommendationPolicy", "按当前知识点生成检索入口，后续可接入真实视频筛选服务");
        }
        return map;
    }

    private String resourceTitle(String type, String knowledgePoint) {
        return switch (type) {
            case "doc" -> "讲义文档：" + knowledgePoint;
            case "html" -> "交互知识页：" + knowledgePoint;
            case "mind_map" -> "思维导图：" + knowledgePoint;
            case "quiz" -> "专项练习：" + knowledgePoint;
            case "image" -> "图解卡片：" + knowledgePoint;
            case "video" -> "短视频脚本：" + knowledgePoint;
            case "link" -> "B站视频推荐：" + knowledgePoint;
            default -> "学习资料：" + knowledgePoint;
        };
    }

    private String resourceDescription(String type, String knowledgePoint, ResourcePackageGenerateDTO dto) {
        String minutes = dto.getTargetMinutes() + "分钟";
        return switch (type) {
            case "doc" -> "按" + minutes + "学习节奏整理核心概念、例题和复盘要点。";
            case "html" -> "以步骤拆解和对比表组织知识点，适合边看边操作。";
            case "mind_map" -> "梳理「" + knowledgePoint + "」的概念层级、易错点和关联知识。";
            case "quiz" -> "围绕当前任务生成巩固题，用于学习后即时检测。";
            case "image" -> "把抽象概念转成一张图解卡片，适合快速理解。";
            case "video" -> "生成可用于后续合成视频的讲解脚本和分镜结构。";
            case "link" -> "按当前知识点生成 B站检索推荐入口，标记为 AI 筛选推荐。";
            default -> "Agent 为当前学习任务生成的配套资料。";
        };
    }

    private String resourceUrl(String type, String knowledgePoint, String packageId) {
        String encoded = URLEncoder.encode(knowledgePoint, StandardCharsets.UTF_8);
        if ("link".equals(type)) {
            return "https://search.bilibili.com/all?keyword=" + encoded;
        }
        return "/learning-resources/generated/" + packageId + "/" + type;
    }

    private String fileFormat(String type) {
        return switch (type) {
            case "doc" -> "md";
            case "html" -> "html";
            case "mind_map", "quiz", "video" -> "json";
            case "image" -> "png";
            default -> "link";
        };
    }

    private String studyScenario(String type) {
        return switch (type) {
            case "doc", "html" -> "学习";
            case "mind_map" -> "复盘";
            case "quiz" -> "测评";
            case "image" -> "理解";
            case "video", "link" -> "补充讲解";
            default -> "学习";
        };
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("资源元数据序列化失败");
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
