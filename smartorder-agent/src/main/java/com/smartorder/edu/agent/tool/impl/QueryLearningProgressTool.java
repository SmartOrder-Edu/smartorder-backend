package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.entity.LearningRecord;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.service.ILearningRecordService;
import com.smartorder.edu.learning.service.ITaskService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询学习进度工具——获取学生的学习记录、任务完成情况
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryLearningProgressTool implements ToolHandler {

    private final ILearningRecordService learningRecordService;
    private final ITaskService taskService;

    @Override
    public String name() {
        return "queryLearningProgress";
    }

    @Override
    public String description() {
        return "查询学生的学习进度，包括近期学习记录、任务完成率、各空间学习时长。当用户询问'学得怎么样'、'最近进度'、'学习情况'时调用此工具";
    }

    @Override
    public Class<?> requestType() {
        return QueryLearningProgressRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROGRESS;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        QueryLearningProgressRequest req = (QueryLearningProgressRequest) request;
        Long userId = context.getUserId();

        log.info("[QueryLearningProgress] userId={}", userId);

        int recentDays = req.getRecentDays() != null ? req.getRecentDays() : 7;

        List<LearningRecord> recentRecords = learningRecordService.getRecentByUserId(userId, recentDays);
        List<Task> todayTasks = taskService.getTodayTasks(userId);
        List<Task> pendingTasks = taskService.getPendingTasks(userId);
        List<Task> overdueTasks = taskService.getOverdueTasks(userId);

        long todayCompleted = todayTasks.stream().filter(t -> "done".equals(t.getStatus())).count();
        long totalPending = pendingTasks.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recentDays", recentDays);
        result.put("recentRecordsCount", recentRecords.size());
        result.put("todayTasksCount", todayTasks.size());
        result.put("todayCompletedCount", todayCompleted);
        result.put("pendingTasksCount", totalPending);
        result.put("overdueTasksCount", overdueTasks.size());

        if (!recentRecords.isEmpty()) {
            int totalMinutes = recentRecords.stream()
                    .mapToInt(r -> r.getStudyDurationMinutes() != null ? r.getStudyDurationMinutes() : 0).sum();
            double avgFocus = recentRecords.stream()
                    .mapToInt(r -> r.getFocusLevel() != null ? r.getFocusLevel() : 0).average().orElse(0);
            result.put("totalStudyMinutes", totalMinutes);
            result.put("averageFocusLevel", Math.round(avgFocus * 10.0) / 10.0);
        }

        log.info("[QueryLearningProgress] result={}", result);
        return result;
    }

    @Data
    public static class QueryLearningProgressRequest {
        @dev.langchain4j.model.output.structured.Description("查询最近几天的学习记录，默认7天")
        private Integer recentDays;
    }
}
