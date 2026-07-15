package com.smartorder.edu.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.entity.LearningRecord;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.service.ILearningRecordService;
import com.smartorder.edu.learning.service.ITaskService;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 查询学习进度工具——获取学生的学习记录、任务完成情况
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryLearningProgressTool implements ToolHandler {

    private final ILearningRecordService learningRecordService;
    private final ITaskService taskService;
    private final EvaluationAnswerRecordMapper answerRecordMapper;

    @Override
    public String name() {
        return "queryLearningProgress";
    }

    @Override
    public String description() {
        return "查询学生的学习进度，包括近期学习记录、任务完成率、各空间学习时长，以及练习测评答题记录统计。"
                + "当用户询问'学得怎么样'、'最近进度'、'学习情况'、'答题情况'、'做题情况'时调用此工具";
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
        List<EvaluationAnswerRecord> recentAnswers = getRecentAnswers(userId, recentDays);

        long todayCompleted = todayTasks.stream().filter(t -> "done".equals(t.getStatus())).count();
        long totalPending = pendingTasks.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recentDays", recentDays);
        result.put("recentRecordsCount", recentRecords.size());
        result.put("todayTasksCount", todayTasks.size());
        result.put("todayCompletedCount", todayCompleted);
        result.put("pendingTasksCount", totalPending);
        result.put("overdueTasksCount", overdueTasks.size());
        result.put("answerStats", buildAnswerStats(recentAnswers));

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

    private List<EvaluationAnswerRecord> getRecentAnswers(Long userId, int recentDays) {
        LocalDateTime startTime = LocalDate.now().minusDays(Math.max(recentDays - 1, 0)).atStartOfDay();
        return answerRecordMapper.selectList(new LambdaQueryWrapper<EvaluationAnswerRecord>()
                .eq(EvaluationAnswerRecord::getUserId, userId)
                .ge(EvaluationAnswerRecord::getCreatedAt, startTime)
                .orderByDesc(EvaluationAnswerRecord::getCreatedAt)
                .last("LIMIT 200"));
    }

    private Map<String, Object> buildAnswerStats(List<EvaluationAnswerRecord> records) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int total = records.size();
        List<EvaluationAnswerRecord> submitted = records.stream()
                .filter(record -> record.getStatus() == null || record.getStatus() >= 2 || record.getIsCorrect() != null)
                .toList();
        List<EvaluationAnswerRecord> graded = submitted.stream()
                .filter(record -> record.getIsCorrect() != null)
                .toList();
        long correctCount = graded.stream().filter(record -> Objects.equals(record.getIsCorrect(), 1)).count();
        int totalDurationSeconds = submitted.stream()
                .mapToInt(record -> record.getDuration() != null ? record.getDuration() : 0)
                .sum();
        BigDecimal totalScore = graded.stream()
                .map(record -> record.getScore() != null ? record.getScore() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.put("totalAnswerRecords", total);
        stats.put("submittedAnswerCount", submitted.size());
        stats.put("gradedAnswerCount", graded.size());
        stats.put("pendingGradingCount", submitted.size() - graded.size());
        stats.put("correctCount", correctCount);
        stats.put("accuracy", graded.isEmpty()
                ? null
                : BigDecimal.valueOf(correctCount)
                .divide(BigDecimal.valueOf(graded.size()), 4, RoundingMode.HALF_UP));
        stats.put("totalScore", totalScore);
        stats.put("totalDurationSeconds", totalDurationSeconds);
        stats.put("recentAnswers", submitted.stream()
                .limit(10)
                .map(this::toAnswerSummary)
                .toList());
        return stats;
    }

    private Map<String, Object> toAnswerSummary(EvaluationAnswerRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("recordId", record.getId());
        item.put("sessionId", record.getSessionId());
        item.put("spaceId", record.getLearningSpaceId());
        item.put("questionId", record.getQuestionId());
        item.put("isCorrect", record.getIsCorrect());
        item.put("score", record.getScore());
        item.put("duration", record.getDuration());
        item.put("usedHint", record.getUsedHint());
        item.put("status", record.getStatus());
        item.put("errorType", record.getErrorType());
        item.put("createdAt", record.getCreatedAt());
        return item;
    }

    @Data
    public static class QueryLearningProgressRequest {
        @dev.langchain4j.model.output.structured.Description("查询最近几天的学习记录，默认7天")
        private Integer recentDays;
    }
}
