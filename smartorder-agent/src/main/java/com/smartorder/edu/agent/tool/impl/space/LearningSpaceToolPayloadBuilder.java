package com.smartorder.edu.agent.tool.impl.space;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.learning.domain.vo.LearningRecordVO;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.domain.vo.PathNodeVO;
import com.smartorder.edu.learning.domain.vo.ResourceVO;
import com.smartorder.edu.learning.domain.vo.SpaceDashboardVO;
import com.smartorder.edu.learning.domain.vo.TaskVO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class LearningSpaceToolPayloadBuilder {

    private static final int LIST_TASK_LIMIT = 3;
    private static final int LIST_PATH_LIMIT = 3;
    private static final int LIST_RESOURCE_LIMIT = 3;
    private static final int SHORT_TEXT_LIMIT = 80;

    Map<String, Object> toSpaceSummary(LearningSpaceVO space, SpaceDashboardVO dashboard) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", stringId(space.getId()));
        result.put("name", space.getName());
        result.put("type", space.getType());
        result.put("status", space.getStatus());
        result.put("currentGoal", shortText(dashboard != null ? valueOrEmpty(dashboard.getCurrentGoal()) : extractPrimaryGoal(space.getGoals())));
        result.put("progress", roundedProgress(space.getProgress()));
        result.put("riskStatus", dashboard != null ? dashboard.getRiskStatus() : deriveRiskStatus(space));
        result.put("daysRemaining", dashboard != null ? dashboard.getDaysRemaining() : daysRemaining(space));
        result.put("todayTaskCount", dashboard != null ? dashboard.getTodayTasks().size() : 0);
        result.put("overdueTaskCount", dashboard != null ? dashboard.getOverdueTasks().size() : 0);
        result.put("agentInsight", shortText(dashboard != null ? valueOrEmpty(dashboard.getAgentInsight()) : ""));
        return result;
    }

    Map<String, Object> toDashboardCardPayload(SpaceDashboardVO dashboard) {
        LearningSpaceVO space = dashboard.getSpace();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("space", toSpaceSummary(space, dashboard));
        Map<String, Object> pathProgress = new LinkedHashMap<>();
        pathProgress.put("completedNodes", dashboard.getPathProgress() != null ? dashboard.getPathProgress().getCompletedNodes() : 0);
        pathProgress.put("totalNodes", dashboard.getPathProgress() != null ? dashboard.getPathProgress().getTotalNodes() : 0);
        pathProgress.put("currentNodeId", dashboard.getPathProgress() != null ? stringId(dashboard.getPathProgress().getCurrentNodeId()) : null);
        pathProgress.put("percent", dashboard.getPathProgress() != null ? dashboard.getPathProgress().getPercent() : 0);
        result.put("pathProgress", pathProgress);
        result.put("todayTasks", dashboard.getTodayTasks().stream().limit(LIST_TASK_LIMIT).map(this::toTaskSummary).toList());
        result.put("overdueTasks", dashboard.getOverdueTasks().stream().limit(LIST_TASK_LIMIT).map(this::toTaskSummary).toList());
        result.put("currentPathNodes", currentPathNodes(dashboard.getPathNodes()).stream()
                .limit(LIST_PATH_LIMIT)
                .map(this::toPathNodeSummary)
                .toList());
        result.put("resources", dashboard.getResources().stream().limit(LIST_RESOURCE_LIMIT).map(this::toResourceSummary).toList());
        result.put("recentStats", toRecentStats(dashboard.getRecentRecords()));
        return result;
    }

    Map<String, Object> toTaskSummary(TaskVO task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", stringId(task.getId()));
        result.put("title", shortText(task.getTitle()));
        result.put("taskType", task.getTaskType());
        result.put("actionType", task.getActionType());
        result.put("targetType", task.getTargetType());
        result.put("targetId", stringId(task.getTargetId()));
        result.put("priority", task.getPriority());
        result.put("status", task.getStatus());
        result.put("dueDate", task.getDueDate() != null ? task.getDueDate().toString() : null);
        result.put("estimatedDurationMinutes", task.getEstimatedDurationMinutes());
        result.put("agentReason", shortText(task.getAgentReason()));
        result.put("aiSuggestion", shortText(task.getAiSuggestion()));
        return result;
    }

    Map<String, Object> toPathNodeSummary(PathNodeVO node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", stringId(node.getId()));
        result.put("title", shortText(node.getTitle()));
        result.put("nodeType", node.getNodeType());
        result.put("difficultyLevel", node.getDifficultyLevel());
        result.put("status", node.getStatus());
        result.put("plannedDurationDays", node.getPlannedDurationDays());
        return result;
    }

    Map<String, Object> toResourceSummary(ResourceVO resource) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", stringId(resource.getId()));
        result.put("title", shortText(resource.getTitle()));
        result.put("resourceType", resource.getResourceType());
        result.put("status", resource.getStatus());
        result.put("isAiGenerated", resource.getIsAiGenerated());
        return result;
    }

    String buildDashboardSummary(SpaceDashboardVO dashboard) {
        LearningSpaceVO space = dashboard.getSpace();
        Map<String, Object> recentStats = toRecentStats(dashboard.getRecentRecords());
        return "学习空间「" + valueOrEmpty(space.getName()) + "」：目标「"
                + valueOrEmpty(dashboard.getCurrentGoal()) + "」，进度 "
                + roundedProgress(space.getProgress()) + "%，风险状态 "
                + valueOrEmpty(dashboard.getRiskStatus()) + "，剩余 "
                + valueOrZero(dashboard.getDaysRemaining()) + " 天。今日任务 "
                + dashboard.getTodayTasks().size() + " 个，逾期任务 "
                + dashboard.getOverdueTasks().size() + " 个，近 7 天学习 "
                + recentStats.get("studyMinutes") + " 分钟。Agent 提醒："
                + valueOrEmpty(dashboard.getAgentInsight());
    }

    String extractPrimaryGoal(String goalsJson) {
        if (goalsJson == null || goalsJson.isBlank()) return "";
        try {
            Object parsed = JSON.parse(goalsJson);
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
            if (parsed instanceof String text) {
                return text;
            }
        } catch (Exception ignored) {
            return goalsJson;
        }
        return goalsJson;
    }

    String deriveRiskStatus(LearningSpaceVO space) {
        if (space == null || !"active".equals(space.getStatus())) return "normal";
        double progress = space.getProgress() != null ? space.getProgress() : 0.0;
        Long remaining = daysRemaining(space);
        if (remaining != null && remaining == 0 && progress < 80) return "danger";
        if (remaining != null && remaining <= 3 && progress < 60) return "warning";
        if (progress >= 70) return "good";
        if (progress < 40) return "warning";
        return "normal";
    }

    Long daysRemaining(LearningSpaceVO space) {
        if (space == null || space.getTargetEndDate() == null) return null;
        return Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), space.getTargetEndDate()));
    }

    int roundedProgress(Double progress) {
        return progress == null ? 0 : (int) Math.round(progress);
    }

    private List<PathNodeVO> currentPathNodes(List<PathNodeVO> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        List<PathNodeVO> current = nodes.stream()
                .filter(n -> "current".equals(n.getStatus()))
                .sorted(Comparator.comparingInt(n -> n.getSortOrder() != null ? n.getSortOrder() : 0))
                .toList();
        if (!current.isEmpty()) return current;
        return nodes.stream()
                .filter(n -> "locked".equals(n.getStatus()))
                .sorted(Comparator.comparingInt(n -> n.getSortOrder() != null ? n.getSortOrder() : 0))
                .limit(LIST_PATH_LIMIT)
                .toList();
    }

    private Map<String, Object> toRecentStats(List<LearningRecordVO> records) {
        List<LearningRecordVO> safeRecords = records != null ? records : new ArrayList<>();
        int studyMinutes = safeRecords.stream()
                .mapToInt(r -> r.getStudyDurationMinutes() != null ? r.getStudyDurationMinutes() : 0)
                .sum();
        int tasksCompleted = safeRecords.stream()
                .mapToInt(r -> r.getTasksCompleted() != null ? r.getTasksCompleted() : 0)
                .sum();
        int tasksPlanned = safeRecords.stream()
                .mapToInt(r -> r.getTasksPlanned() != null ? r.getTasksPlanned() : 0)
                .sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordDays", safeRecords.size());
        result.put("studyMinutes", studyMinutes);
        result.put("tasksCompleted", tasksCompleted);
        result.put("tasksPlanned", tasksPlanned);
        result.put("completionRate", tasksPlanned > 0 ? Math.round(tasksCompleted * 100.0 / tasksPlanned) : 0);
        return result;
    }

    private String stringId(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private String shortText(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= SHORT_TEXT_LIMIT) return trimmed;
        return trimmed.substring(0, SHORT_TEXT_LIMIT) + "...";
    }
}
