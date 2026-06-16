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

    private final ILearningSpaceService learningSpaceService;
    private final ITaskService taskService;
    private final ILearningPathNodeService pathNodeService;
    private final ILearningPathEdgeService pathEdgeService;
    private final IResourceService resourceService;
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
                .toList();
        dashboard.setPathNodes(pathNodeVOs);

        // Compute node statuses
        computeNodeStatuses(pathNodeVOs);
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

        // 13. Agent actions (empty for MVP)
        dashboard.setRecentAgentActions(Collections.emptyList());

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
        vo.setPriority(task.getPriority());
        vo.setStatus(task.getStatus());
        vo.setDueDate(task.getDueDate());
        vo.setDueTime(task.getDueTime());
        vo.setEstimatedDurationMinutes(task.getEstimatedDurationMinutes());
        vo.setActualDurationMinutes(task.getActualDurationMinutes());
        vo.setCompletedAt(task.getCompletedAt());
        vo.setAiSuggestion(task.getAiSuggestion());
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
        vo.setIsAiGenerated(r.getIsAiGenerated() != null && r.getIsAiGenerated() == 1);
        vo.setSource(r.getSource());
        return vo;
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

    private void computeNodeStatuses(List<PathNodeVO> nodes) {
        if (nodes.isEmpty()) return;

        // Sort by sortOrder
        nodes.sort(Comparator.comparingInt(n -> n.getSortOrder() != null ? n.getSortOrder() : 0));

        // Find the first node that is not completed (due to lack of data, all nodes
        // default to "locked" in toPathNodeVO; we only mark as "completed" or "current" here)
        //
        // For MVP: first node = "current", rest = "locked"
        // In the future, we can compute "completed" based on task completion data
        boolean foundCurrent = false;
        for (PathNodeVO node : nodes) {
            if (!foundCurrent) {
                node.setStatus("current");
                foundCurrent = true;
            }
            // rest remain "locked" (default)
        }
    }

    private PathProgress computePathProgress(List<PathNodeVO> nodes) {
        int total = nodes.size();
        long completed = nodes.stream().filter(n -> "completed".equals(n.getStatus())).count();
        Long currentNodeId = nodes.stream()
                .filter(n -> "current".equals(n.getStatus()))
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
