package com.smartorder.edu.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResourceGenerationContextBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ILearningSpaceService learningSpaceService;
    private final ITaskService taskService;
    private final ILearningPathNodeService pathNodeService;

    public ResourceGenerationContext build(Long userId, Resource resource) {
        Map<String, Object> metadata = parseMetadata(resource.getMetadata());
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, resource.getSpaceId());
        Task task = resolveTask(resource);
        LearningPathNode pathNode = resolvePathNode(resource);
        String knowledgePoint = firstNonBlank(
                asString(metadata.get("knowledgePoint")),
                task == null ? null : task.getTitle(),
                pathNode == null ? null : pathNode.getTitle(),
                cleanTitle(resource),
                space == null ? null : space.getName(),
                "当前知识点"
        );
        String reason = firstNonBlank(
                asString(metadata.get("agentReason")),
                resource.getDescription(),
                "Resource Agent 围绕「" + knowledgePoint + "」生成可直接学习的资源"
        );
        return new ResourceGenerationContext(
                resource,
                space,
                task,
                pathNode,
                knowledgePoint,
                reason,
                firstNonBlank(asString(metadata.get("studyScenario")), "学习"),
                asString(metadata.get("userIntent")),
                asString(metadata.get("packageMode")),
                extractTargetMinutes(metadata),
                metadata
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(metadata, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private Task resolveTask(Resource resource) {
        if (resource.getTaskId() == null || resource.getSpaceId() == null) {
            return null;
        }
        return taskService.getBySpaceId(resource.getSpaceId()).stream()
                .filter(task -> resource.getTaskId().equals(task.getId()))
                .findFirst()
                .orElse(null);
    }

    private LearningPathNode resolvePathNode(Resource resource) {
        if (resource.getPathNodeId() == null || resource.getSpaceId() == null) {
            return null;
        }
        return pathNodeService.getBySpaceId(resource.getSpaceId()).stream()
                .filter(node -> resource.getPathNodeId().equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private int extractTargetMinutes(Map<String, Object> metadata) {
        Object value = metadata.get("targetMinutes");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (Exception e) {
            return 30;
        }
    }

    private String cleanTitle(Resource resource) {
        String title = resource.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }
        int index = title.indexOf("：");
        return index >= 0 && index + 1 < title.length() ? title.substring(index + 1) : title;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
