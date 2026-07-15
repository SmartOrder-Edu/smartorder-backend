package com.smartorder.edu.learning.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.entity.*;
import com.smartorder.edu.learning.domain.vo.*;
import com.smartorder.edu.learning.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpaceDashboardServiceImpl implements ISpaceDashboardService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PATH_STATUS_COMPLETED = "completed";
    private static final String PATH_STATUS_CURRENT = "current";
    private static final String PATH_STATUS_LOCKED = "locked";

    private final ILearningSpaceService learningSpaceService;
    private final ITaskService taskService;
    private final ILearningPathNodeService pathNodeService;
    private final ILearningPathEdgeService pathEdgeService;
    private final IResourceService resourceService;
    private final IResourceGenerationJobService resourceGenerationJobService;
    private final ILearningRecordService learningRecordService;

    @Override
    public SpaceDashboardVO getDashboard(Long userId, Long spaceId) {
        SpaceDashboardVO dashboard = new SpaceDashboardVO();

        // 1. Get space (validates ownership)
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, spaceId);
        dashboard.setSpace(space);
        dashboard.setProgress(space.getProgress() != null ? space.getProgress() : 0.0);

        // 2. Current goal
        dashboard.setCurrentGoal(extractCurrentGoal(space.getGoals()));

        // 3. Tasks
        LocalDate today = LocalDate.now();
        List<Task> spaceAllTasks = taskService.getBySpaceId(spaceId);

        List<Task> todayTaskList = taskService.getBySpaceIdAndDate(spaceId, userId, today);
        List<Task> pendingTaskList = taskService.getPendingBySpaceId(spaceId, userId);
        List<Task> overdueTaskList = taskService.getOverdueBySpaceId(spaceId, userId);

        dashboard.setTodayTasks(todayTaskList.stream().map(this::toTaskVO).toList());
        dashboard.setPendingTasks(pendingTaskList.stream().map(this::toTaskVO).toList());
        dashboard.setOverdueTasks(overdueTaskList.stream().map(this::toTaskVO).toList());

        // 4. Path nodes with computed status
        List<LearningPathNode> nodes = pathNodeService.getBySpaceId(spaceId);
        Map<Long, List<Task>> nodeTasksMap = spaceAllTasks.stream()
                .filter(t -> t.getPathNodeId() != null)
                .collect(Collectors.groupingBy(Task::getPathNodeId));

        List<PathNodeVO> pathNodeVOs = nodes.stream()
                .map(n -> toPathNodeVO(n, nodeTasksMap))
                .collect(Collectors.toCollection(ArrayList::new));
        dashboard.setPathNodes(pathNodeVOs);

        // Compute node statuses from real task progress
        computeNodeStatuses(pathNodeVOs, nodeTasksMap);
        pathNodeVOs.forEach(n -> log.debug("Node {} status: {}", n.getTitle(), n.getStatus()));

        // 5. Path edges
        List<LearningPathEdge> edges = pathEdgeService.getBySpaceId(spaceId);
        dashboard.setPathEdges(edges.stream().map(this::toPathEdgeVO).toList());

        // 6. Path progress
        dashboard.setPathProgress(computePathProgress(pathNodeVOs));

        // 7. Resources
        List<Resource> resources = resourceService.getBySpaceId(spaceId);
        dashboard.setResources(resources.stream().map(this::toResourceVO).toList());

        // 8. Recent records
        LocalDate sevenDaysAgo = today.minusDays(6);
        List<LearningRecord> recentRecords = learningRecordService.getBySpaceIdAndDateRange(spaceId, userId, sevenDaysAgo, today);
        dashboard.setRecentRecords(recentRecords.stream().map(this::toLearningRecordVO).toList());

        // 9. Streak days
        dashboard.setStreakDays(computeStreakDays(recentRecords, today));

        // 10. Risk status
        dashboard.setRiskStatus(computeRiskStatus(space, overdueTaskList.size()));

        // 11. Agent insight
        dashboard.setAgentInsight(generateAgentInsight(overdueTaskList.size(), dashboard.getRiskStatus(),
                space.getProgress() != null ? space.getProgress() / 100.0 : 0,
                computeTimeElapsedRate(space)));

        // 12. Days remaining
        dashboard.setDaysRemaining(computeDaysRemaining(space));

        // 13. Recent Resource Agent actions
        List<ResourceGenerationJob> recentResourceJobs = resourceGenerationJobService.getRecentBySpaceId(spaceId, 8);
        dashboard.setRecentAgentActions(recentResourceJobs.stream().map(this::toAgentActionVO).toList());

        return dashboard;
    }

    // ============ Conversion Methods ============

    private TaskVO toTaskVO(Task task) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setSpaceId(task.getSpaceId());
        vo.setPathNodeId(task.getPathNodeId());
        vo.setTitle(task.getTitle());
        vo.setDescription(task.getDescription());
        vo.setTaskType(task.getTaskType());
        vo.setActionType(task.getActionType());
        vo.setTargetType(task.getTargetType());
        vo.setTargetId(task.getTargetId());
        vo.setPriority(task.getPriority());
        vo.setStatus(task.getStatus());
        vo.setDueDate(task.getDueDate());
        vo.setDueTime(task.getDueTime());
        vo.setEstimatedDurationMinutes(task.getEstimatedDurationMinutes());
        vo.setActualDurationMinutes(task.getActualDurationMinutes());
        vo.setCompletedAt(task.getCompletedAt());
        vo.setAiSuggestion(task.getAiSuggestion());
        vo.setAgentReason(task.getAgentReason());
        vo.setMetadata(task.getMetadata());

        LocalDate today = LocalDate.now();
        vo.setIsToday(task.getDueDate() != null && task.getDueDate().equals(today));
        vo.setIsOverdue(task.getDueDate() != null && task.getDueDate().isBefore(today)
                && !"completed".equals(task.getStatus()) && !"cancelled".equals(task.getStatus()));
        return vo;
    }

    private PathNodeVO toPathNodeVO(LearningPathNode node, Map<Long, List<Task>> nodeTasksMap) {
        PathNodeVO vo = new PathNodeVO();
        vo.setId(node.getId());
        vo.setSpaceId(node.getSpaceId());
        vo.setParentNodeId(node.getParentNodeId());
        vo.setKpUid(node.getKpUid());
        vo.setTitle(node.getTitle());
        vo.setDescription(node.getDescription());
        vo.setNodeType(node.getNodeType());
        vo.setEstimatedDurationMinutes(node.getEstimatedDurationMinutes());
        vo.setPlannedDurationDays(node.getPlannedDurationDays());
        vo.setDifficultyLevel(mapDifficulty(node.getDifficultyLevel()));
        vo.setSortOrder(node.getSortOrder());
        vo.setIsMilestone(node.getIsMilestone() != null && node.getIsMilestone() == 1);
        // Status computed later
        vo.setStatus("locked");
        return vo;
    }

    private PathEdgeVO toPathEdgeVO(LearningPathEdge edge) {
        PathEdgeVO vo = new PathEdgeVO();
        vo.setId(edge.getId());
        vo.setSpaceId(edge.getSpaceId());
        vo.setFromNodeId(edge.getFromNodeId());
        vo.setToNodeId(edge.getToNodeId());
        vo.setEdgeType(edge.getEdgeType());
        return vo;
    }

    private ResourceVO toResourceVO(Resource r) {
        ResourceVO vo = new ResourceVO();
        vo.setId(r.getId());
        vo.setSpaceId(r.getSpaceId());
        vo.setPathNodeId(r.getPathNodeId());
        vo.setTaskId(r.getTaskId());
        vo.setTitle(r.getTitle());
        vo.setDescription(r.getDescription());
        vo.setResourceType(r.getResourceType());
        vo.setUrl(r.getUrl());
        vo.setFileFormat(r.getFileFormat());
        vo.setCoverUrl(r.getCoverUrl());
        vo.setIsAiGenerated(r.getIsAiGenerated() != null && r.getIsAiGenerated() == 1);
        vo.setSource(r.getSource());
        vo.setStatus(r.getStatus());
        vo.setMetadata(r.getMetadata());
        enrichResourceVOFromMetadata(vo, r.getMetadata());
        return vo;
    }

    private AgentActionVO toAgentActionVO(ResourceGenerationJob job) {
        AgentActionVO vo = new AgentActionVO();
        vo.setId(job.getId());
        vo.setAgentType("resource");
        vo.setActionType("generate_resource");
        vo.setTargetType("resource");
        vo.setTargetId(job.getResourceId());
        vo.setReason(job.getReason() != null && !job.getReason().isBlank()
                ? job.getReason()
                : "Resource Agent 正在生成「" + job.getTitle() + "」");
        vo.setStatus(mapJobStatus(job.getStatus()));
        vo.setCreateTime(job.getCreateTime());
        return vo;
    }

    private String mapJobStatus(String status) {
        return switch (String.valueOf(status)) {
            case "pending" -> "pending";
            case "generating" -> "running";
            case "generated" -> "success";
            case "failed" -> "failed";
            default -> "pending";
        };
    }

    @SuppressWarnings("unchecked")
    private void enrichResourceVOFromMetadata(ResourceVO vo, String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(metadata, Map.class);
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

    private LearningRecordVO toLearningRecordVO(LearningRecord r) {
        LearningRecordVO vo = new LearningRecordVO();
        vo.setId(r.getId());
        vo.setRecordDate(r.getRecordDate());
        vo.setStudyDurationMinutes(r.getStudyDurationMinutes());
        vo.setTasksCompleted(r.getTasksCompleted());
        vo.setTasksPlanned(r.getTasksPlanned());
        vo.setFocusLevel(r.getFocusLevel());
        vo.setMood(mapMood(r.getMood()));
        vo.setSelfRating(r.getSelfRating());
        vo.setAiReflection(r.getAiReflection());
        return vo;
    }

    // ============ Computation Methods ============

    private String extractCurrentGoal(String goalsJson) {
        if (goalsJson == null || goalsJson.isBlank()) return "";
        try {
            List<String> goals = objectMapper.readValue(goalsJson, new TypeReference<List<String>>() {});
            return goals != null && !goals.isEmpty() ? goals.get(0) : "";
        } catch (Exception e) {
            log.warn("Failed to parse goals JSON: {}", goalsJson, e);
            return "";
        }
    }

    private void computeNodeStatuses(List<PathNodeVO> nodes, Map<Long, List<Task>> nodeTasksMap) {
        if (nodes.isEmpty()) return;

        nodes.sort(Comparator.comparingInt(n -> n.getSortOrder() != null ? n.getSortOrder() : 0));
        Map<Long, PathNodeVO> nodeById = nodes.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.toMap(PathNodeVO::getId, n -> n, (a, b) -> a, LinkedHashMap::new));
        Map<Long, List<PathNodeVO>> childrenByParentId = nodes.stream()
                .filter(n -> n.getParentNodeId() != null)
                .collect(Collectors.groupingBy(
                        PathNodeVO::getParentNodeId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            list.sort(Comparator.comparingInt(n -> n.getSortOrder() != null ? n.getSortOrder() : 0));
                            return list;
                        })
                ));

        Set<Long> visiting = new HashSet<>();
        for (PathNodeVO node : nodes) {
            computeNodeStatus(node, nodeTasksMap, childrenByParentId, visiting);
        }

        boolean hasCurrent = nodes.stream().anyMatch(n -> PATH_STATUS_CURRENT.equals(n.getStatus()));
        boolean hasUnlocked = nodes.stream().anyMatch(n -> !PATH_STATUS_LOCKED.equals(n.getStatus()));
        if (!hasCurrent && !hasUnlocked) {
            firstPathNode(nodes).ifPresent(n -> n.setStatus(PATH_STATUS_CURRENT));
            return;
        }

        if (!hasCurrent) {
            firstLockedPathNode(nodes).ifPresent(n -> n.setStatus(PATH_STATUS_CURRENT));
        }

        // Keep descendants consistent when a stage becomes current only because it is next in sequence.
        nodeById.values().forEach(node -> {
            if (PATH_STATUS_CURRENT.equals(node.getStatus())) {
                markFirstLockedChildCurrent(node, childrenByParentId);
            }
        });
    }

    private String computeNodeStatus(
            PathNodeVO node,
            Map<Long, List<Task>> nodeTasksMap,
            Map<Long, List<PathNodeVO>> childrenByParentId,
            Set<Long> visiting
    ) {
        if (node.getId() == null) {
            node.setStatus(PATH_STATUS_LOCKED);
            return node.getStatus();
        }
        if (!visiting.add(node.getId())) {
            log.warn("Detected circular learning path node hierarchy at node {}", node.getId());
            node.setStatus(PATH_STATUS_LOCKED);
            return node.getStatus();
        }

        List<PathNodeVO> children = childrenByParentId.getOrDefault(node.getId(), Collections.emptyList());
        String taskStatus = statusFromTasks(nodeTasksMap.getOrDefault(node.getId(), Collections.emptyList()));
        if (children.isEmpty()) {
            node.setStatus(taskStatus);
            visiting.remove(node.getId());
            return node.getStatus();
        }

        List<String> childStatuses = children.stream()
                .map(child -> computeNodeStatus(child, nodeTasksMap, childrenByParentId, visiting))
                .toList();

        if (!childStatuses.isEmpty() && childStatuses.stream().allMatch(PATH_STATUS_COMPLETED::equals)) {
            node.setStatus(PATH_STATUS_COMPLETED);
        } else if (PATH_STATUS_CURRENT.equals(taskStatus)
                || PATH_STATUS_COMPLETED.equals(taskStatus)
                || childStatuses.stream().anyMatch(s -> PATH_STATUS_CURRENT.equals(s) || PATH_STATUS_COMPLETED.equals(s))) {
            node.setStatus(PATH_STATUS_CURRENT);
        } else {
            node.setStatus(PATH_STATUS_LOCKED);
        }

        visiting.remove(node.getId());
        return node.getStatus();
    }

    private String statusFromTasks(List<Task> tasks) {
        List<Task> effectiveTasks = tasks.stream()
                .filter(task -> !"cancelled".equals(task.getStatus()))
                .toList();
        if (effectiveTasks.isEmpty()) {
            return PATH_STATUS_LOCKED;
        }
        boolean allCompleted = effectiveTasks.stream().allMatch(task -> PATH_STATUS_COMPLETED.equals(task.getStatus()));
        if (allCompleted) {
            return PATH_STATUS_COMPLETED;
        }
        boolean hasStarted = effectiveTasks.stream().anyMatch(task ->
                PATH_STATUS_COMPLETED.equals(task.getStatus())
                        || PATH_STATUS_CURRENT.equals(task.getStatus())
                        || "in_progress".equals(task.getStatus())
                        || "doing".equals(task.getStatus())
                        || "overdue".equals(task.getStatus())
        );
        return hasStarted ? PATH_STATUS_CURRENT : PATH_STATUS_LOCKED;
    }

    private Optional<PathNodeVO> firstPathNode(List<PathNodeVO> nodes) {
        return visiblePathNodes(nodes).stream().findFirst()
                .or(() -> nodes.stream().findFirst());
    }

    private Optional<PathNodeVO> firstLockedPathNode(List<PathNodeVO> nodes) {
        return visiblePathNodes(nodes).stream()
                .filter(n -> PATH_STATUS_LOCKED.equals(n.getStatus()))
                .findFirst()
                .or(() -> nodes.stream().filter(n -> PATH_STATUS_LOCKED.equals(n.getStatus())).findFirst());
    }

    private List<PathNodeVO> visiblePathNodes(List<PathNodeVO> nodes) {
        return nodes.stream()
                .filter(this::isVisiblePathNode)
                .sorted(Comparator.comparingInt(n -> n.getSortOrder() != null ? n.getSortOrder() : 0))
                .toList();
    }

    private boolean isVisiblePathNode(PathNodeVO node) {
        return node.getParentNodeId() == null || "stage".equals(node.getNodeType());
    }

    private void markFirstLockedChildCurrent(PathNodeVO node, Map<Long, List<PathNodeVO>> childrenByParentId) {
        List<PathNodeVO> children = childrenByParentId.getOrDefault(node.getId(), Collections.emptyList());
        Optional<PathNodeVO> childToOpen = children.stream()
                .filter(child -> PATH_STATUS_LOCKED.equals(child.getStatus()))
                .findFirst();
        if (childToOpen.isPresent()
                && children.stream().noneMatch(child -> PATH_STATUS_CURRENT.equals(child.getStatus()))) {
            childToOpen.get().setStatus(PATH_STATUS_CURRENT);
        }
    }

    private PathProgress computePathProgress(List<PathNodeVO> nodes) {
        List<PathNodeVO> progressNodes = visiblePathNodes(nodes);
        if (progressNodes.isEmpty()) {
            progressNodes = nodes;
        }
        int total = progressNodes.size();
        long completed = progressNodes.stream().filter(n -> PATH_STATUS_COMPLETED.equals(n.getStatus())).count();
        Long currentNodeId = progressNodes.stream()
                .filter(n -> PATH_STATUS_CURRENT.equals(n.getStatus()))
                .map(PathNodeVO::getId)
                .findFirst()
                .orElse(null);
        int percent = total > 0 ? (int) (completed * 100 / total) : 0;
        return new PathProgress((int) completed, total, currentNodeId, percent);
    }

    private Integer computeStreakDays(List<LearningRecord> records, LocalDate today) {
        Set<LocalDate> dateSet = records.stream()
                .map(LearningRecord::getRecordDate)
                .collect(Collectors.toSet());

        int streak = 0;
        LocalDate date = today;
        while (dateSet.contains(date)) {
            streak++;
            date = date.minusDays(1);
        }
        return streak;
    }

    private double computeTimeElapsedRate(LearningSpaceVO space) {
        if (space.getStartDate() == null || space.getTargetEndDate() == null) return 0;
        long totalDays = ChronoUnit.DAYS.between(space.getStartDate(), space.getTargetEndDate());
        if (totalDays <= 0) return 0;
        long elapsedDays = ChronoUnit.DAYS.between(space.getStartDate(), LocalDate.now());
        return Math.max(0, (double) elapsedDays / totalDays);
    }

    private String computeRiskStatus(LearningSpaceVO space, int overdueCount) {
        double progressRate = (space.getProgress() != null ? space.getProgress() : 0) / 100.0;
        double timeElapsedRate = computeTimeElapsedRate(space);

        if (overdueCount > 3 || progressRate < timeElapsedRate * 0.5) return "danger";
        if (overdueCount > 0 || progressRate < timeElapsedRate * 0.8) return "warning";
        if (progressRate < timeElapsedRate) return "normal";
        return "good";
    }

    private String generateAgentInsight(int overdueCount, String riskStatus, double progressRate, double timeElapsedRate) {
        if (overdueCount >= 3) {
            return "你有" + overdueCount + "个逾期任务，建议优先处理逾期任务或调整学习计划";
        } else if (overdueCount >= 1) {
            return "你有" + overdueCount + "个任务已逾期，建议尽快完成";
        } else if ("danger".equals(riskStatus)) {
            return "当前进度已严重落后于计划，建议加大学习强度或调整计划";
        } else if ("warning".equals(riskStatus)) {
            return "当前进度稍落后于计划，建议加快节奏";
        } else if (progressRate >= timeElapsedRate + 0.1) {
            return "当前学习进度良好，保持当前节奏";
        } else {
            return "一切正常，保持当前学习节奏";
        }
    }

    private Long computeDaysRemaining(LearningSpaceVO space) {
        if (space.getTargetEndDate() == null) return 0L;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), space.getTargetEndDate());
        return Math.max(0, days);
    }

    // ============ Mapping Helpers ============

    private String mapDifficulty(Integer level) {
        if (level == null) return "基础";
        return switch (level) {
            case 1 -> "基础";
            case 2 -> "中等";
            case 3 -> "困难";
            case 4 -> "综合";
            default -> "基础";
        };
    }

    private String mapMood(Integer mood) {
        if (mood == null) return null;
        return switch (mood) {
            case 1 -> "tired";
            case 2 -> "low";
            case 3 -> "normal";
            case 4 -> "focused";
            case 5 -> "motivated";
            default -> "normal";
        };
    }
}
