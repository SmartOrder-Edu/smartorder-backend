package com.smartorder.edu.student.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.config.EvaluationRabbitConfig;
import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationLearningReport;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.mq.ReportGenerateMessage;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import com.smartorder.edu.student.mapper.EvaluationLearningReportMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.mapper.EvaluationSessionMapper;
import com.smartorder.edu.student.service.impl.EvaluationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习报告异步生成消费者。
 *
 * <p>判分流程或提交流程只投递生成消息；本消费者负责等待所有题目判完、聚合确定性统计、
 * 调用 AI 做文案增强，并把最终报告落入 evaluation_learning_report。</p>
 */
@Slf4j
@Component
@RabbitListener(queues = EvaluationRabbitConfig.Q_REPORT)
@RequiredArgsConstructor
public class ReportGenerateConsumer {

    private static final String REPORT_TYPE_SINGLE_TEST = "single_test";
    private static final String STATUS_WAITING_GRADING = "waiting_grading";
    private static final String STATUS_GENERATING = "generating";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_FAILED = "failed";
    private static final String ANALYSIS_VERSION = "v1";

    private final EvaluationAnswerRecordMapper answerRecordMapper;
    private final EvaluationQuestionMapper questionMapper;
    private final EvaluationSessionMapper sessionMapper;
    private final EvaluationLearningReportMapper reportMapper;
    private final EvaluationCacheService cacheService;
    private final QuestionAiService questionAiService;
    private final ObjectMapper objectMapper;

    @RabbitHandler
    public void generateReport(ReportGenerateMessage msg) {
        Long sessionId = msg.sessionId();
        Long userId = msg.userId();
        log.info("收到报告生成消息: sessionId={}, userId={}", sessionId, userId);

        EvaluationSession session = sessionMapper.selectById(sessionId);
        String reportKey = EvaluationRedisConstants.sessionReportKey(sessionId, userId);

        try {
            EvaluationLearningReport existing = findReport(sessionId, userId);
            if (existing != null && STATUS_READY.equals(existing.getStatus())) {
                log.info("报告已生成，跳过重复消息: sessionId={}, userId={}", sessionId, userId);
                cacheService.set(reportKey, toVO(existing, session), EvaluationRedisConstants.TTL_REPORT);
                return;
            }

            List<EvaluationAnswerRecord> records = answerRecordMapper.selectList(
                    new LambdaQueryWrapper<EvaluationAnswerRecord>()
                            .eq(EvaluationAnswerRecord::getSessionId, sessionId)
                            .eq(EvaluationAnswerRecord::getUserId, userId));

            if (records.isEmpty()) {
                markFailed(sessionId, userId, session, "未找到答题记录，暂无法生成练习分析报告。");
                return;
            }

            int pendingCount = (int) records.stream()
                    .filter(r -> r.getIsCorrect() == null)
                    .count();
            if (pendingCount > 0) {
                EvaluationLearningReport waiting = upsertStatus(existing, session, sessionId, userId,
                        records, STATUS_WAITING_GRADING, pendingCount, null);
                cacheService.set(reportKey, toVO(waiting, session), EvaluationRedisConstants.TTL_REPORT_GENERATING);
                log.info("报告等待判分完成: sessionId={}, pendingCount={}", sessionId, pendingCount);
                return;
            }

            EvaluationLearningReport running = upsertStatus(existing, session, sessionId, userId,
                    records, STATUS_GENERATING, 0, null);
            Map<Long, EvaluationQuestion> questionMap = loadQuestionMap(records);
            SessionReportVO report = buildReport(sessionId, session, records, questionMap);
            enhanceWithAi(report, records, questionMap);

            EvaluationLearningReport saved = applyReport(running, report);
            if (saved.getId() == null) {
                reportMapper.insert(saved);
            } else {
                reportMapper.updateById(saved);
            }

            SessionReportVO savedVO = toVO(saved, session);
            cacheService.set(reportKey, savedVO, EvaluationRedisConstants.TTL_REPORT);
            log.info("报告生成完成: sessionId={}, userId={}, reportId={}", sessionId, userId, saved.getId());
        } catch (Exception e) {
            log.error("报告生成失败: sessionId={}, userId={}", sessionId, userId, e);
            markFailed(sessionId, userId, session, "报告生成失败: " + e.getMessage());
        }
    }

    private EvaluationLearningReport findReport(Long sessionId, Long userId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<EvaluationLearningReport>()
                .eq(EvaluationLearningReport::getSessionId, sessionId)
                .eq(EvaluationLearningReport::getUserId, userId)
                .eq(EvaluationLearningReport::getReportType, REPORT_TYPE_SINGLE_TEST)
                .orderByDesc(EvaluationLearningReport::getUpdatedAt)
                .last("LIMIT 1"));
    }

    private EvaluationLearningReport upsertStatus(EvaluationLearningReport report,
                                                   EvaluationSession session,
                                                   Long sessionId,
                                                   Long userId,
                                                   List<EvaluationAnswerRecord> records,
                                                   String status,
                                                   int pendingCount,
                                                   String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        if (report == null) {
            report = new EvaluationLearningReport();
            report.setUserId(userId);
            report.setLearningSpaceId(session != null ? session.getLearningSpaceId() : firstSpaceId(records));
            report.setSessionId(sessionId);
            report.setReportType(REPORT_TYPE_SINGLE_TEST);
            report.setRetryCount(0);
            report.setCreatedAt(now);
        }

        report.setStatus(status);
        report.setPendingCount(pendingCount);
        report.setErrorMessage(errorMessage);
        report.setAnalysisVersion(ANALYSIS_VERSION);
        report.setTotalScore(totalScore(records));
        report.setActualScore(actualScore(records));
        report.setAccuracy(accuracy(report.getActualScore(), report.getTotalScore()));
        if (STATUS_GENERATING.equals(status)) {
            report.setStartedAt(now);
            report.setFinishedAt(null);
        }
        if (STATUS_FAILED.equals(status) || STATUS_READY.equals(status)) {
            report.setFinishedAt(now);
        }
        report.setUpdatedAt(now);

        if (report.getId() == null) {
            reportMapper.insert(report);
        } else {
            reportMapper.updateById(report);
        }
        return report;
    }

    private SessionReportVO buildReport(Long sessionId,
                                        EvaluationSession session,
                                        List<EvaluationAnswerRecord> records,
                                        Map<Long, EvaluationQuestion> questionMap) {
        Map<String, KnowledgeStats> knowledgeStats = new LinkedHashMap<>();
        Map<String, KnowledgeStats> abilityStats = new LinkedHashMap<>();

        for (EvaluationAnswerRecord record : records) {
            EvaluationQuestion question = questionMap.get(record.getQuestionId());
            if (question == null || record.getIsCorrect() == null) {
                continue;
            }
            for (String label : knowledgeLabels(question)) {
                knowledgeStats.computeIfAbsent(label, KnowledgeStats::new).accept(record, question);
            }
            abilityStats.computeIfAbsent(abilityLabel(question), KnowledgeStats::new).accept(record, question);
        }

        Map<String, Object> masterySummary = new LinkedHashMap<>();
        List<Map<String, Object>> weakPoints = new ArrayList<>();
        List<Map<String, Object>> strongPoints = new ArrayList<>();
        for (KnowledgeStats stats : knowledgeStats.values()) {
            Map<String, Object> item = stats.toMap("knowledgePoint");
            masterySummary.put(stats.label, item);
            if (stats.scoreRate().compareTo(BigDecimal.valueOf(0.60)) < 0) {
                weakPoints.add(item);
            } else if (stats.scoreRate().compareTo(BigDecimal.valueOf(0.80)) >= 0) {
                strongPoints.add(item);
            }
        }

        Map<String, Object> abilityEvaluation = new LinkedHashMap<>();
        for (KnowledgeStats stats : abilityStats.values()) {
            abilityEvaluation.put(stats.label, stats.toMap("ability"));
        }

        BigDecimal total = totalScore(records);
        BigDecimal actual = actualScore(records);
        List<Map<String, Object>> suggestions = fallbackSuggestions(weakPoints, records, questionMap);
        Map<String, Object> nextRecommendation = buildNextRecommendation(weakPoints, strongPoints, records);

        return SessionReportVO.builder()
                .sessionId(sessionId)
                .title(session != null ? session.getTopic() : null)
                .reportType(REPORT_TYPE_SINGLE_TEST)
                .status(STATUS_READY)
                .message("练习分析报告已生成。")
                .pendingCount(0)
                .totalScore(total)
                .actualScore(actual)
                .accuracy(accuracy(actual, total))
                .masterySummary(masterySummary)
                .weakPoints(weakPoints)
                .strongPoints(strongPoints)
                .abilityEvaluation(abilityEvaluation)
                .learningSuggestions(suggestions)
                .nextRecommendationJson(nextRecommendation)
                .reportContent(fallbackReportContent(session, total, actual, weakPoints, strongPoints, suggestions))
                .generatedBy("system")
                .createdAt(LocalDateTime.now())
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private void enhanceWithAi(SessionReportVO report,
                               List<EvaluationAnswerRecord> records,
                               Map<Long, EvaluationQuestion> questionMap) {
        try {
            String prompt = buildAiPrompt(report, records, questionMap);
            StringBuilder response = new StringBuilder();
            questionAiService.generateQuestions(prompt).doOnNext(response::append).blockLast();
            String json = stripJsonFence(response.toString());
            if (json.isBlank()) {
                return;
            }

            JsonNode root = objectMapper.readTree(json);
            if (root.hasNonNull("reportContent")) {
                report.setReportContent(root.get("reportContent").asText());
            }
            if (root.has("learningSuggestions") && root.get("learningSuggestions").isArray()) {
                report.setLearningSuggestions(objectMapper.convertValue(
                        root.get("learningSuggestions"),
                        new TypeReference<List<Map<String, Object>>>() {
                        }));
            }
            if (root.has("nextRecommendation") && root.get("nextRecommendation").isObject()) {
                report.setNextRecommendationJson(objectMapper.convertValue(
                        root.get("nextRecommendation"),
                        new TypeReference<Map<String, Object>>() {
                        }));
            }
            report.setGeneratedBy("agent");
        } catch (Exception e) {
            log.warn("AI 报告文案增强失败，保留确定性报告: sessionId={}, reason={}",
                    report.getSessionId(), e.getMessage());
            report.setGeneratedBy("system");
        }
    }

    private String buildAiPrompt(SessionReportVO report,
                                 List<EvaluationAnswerRecord> records,
                                 Map<Long, EvaluationQuestion> questionMap) {
        List<Map<String, Object>> wrongEvidence = records.stream()
                .filter(r -> r.getIsCorrect() != null && r.getIsCorrect() == 0)
                .limit(8)
                .map(r -> {
                    EvaluationQuestion q = questionMap.get(r.getQuestionId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("questionId", r.getQuestionId());
                    item.put("questionType", q != null ? q.getQuestionType() : null);
                    item.put("knowledgePoints", q != null ? knowledgeLabels(q) : List.of());
                    item.put("stem", q != null ? shortText(q.getStem(), 180) : "");
                    item.put("score", r.getScore());
                    item.put("maxScore", q != null ? q.getScore() : null);
                    item.put("errorType", r.getErrorType());
                    item.put("errorReason", r.getErrorReason());
                    item.put("judgeResult", r.getJudgeResult());
                    return item;
                })
                .toList();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", report.getTitle());
        input.put("totalScore", report.getTotalScore());
        input.put("actualScore", report.getActualScore());
        input.put("accuracy", report.getAccuracy());
        input.put("weakPoints", report.getWeakPoints());
        input.put("strongPoints", report.getStrongPoints());
        input.put("abilityEvaluation", report.getAbilityEvaluation());
        input.put("wrongEvidence", wrongEvidence);

        return """
                你是一个严谨的 AI 学习分析师。请基于给定 JSON 生成一次练习后的学习分析报告。
                要求：
                1. 不要重新计算分数，只引用输入里的统计结果。
                2. 诊断必须基于错题证据、知识点和能力维度，不要泛泛鼓励。
                3. 建议必须具体、可执行，最多 4 条。
                4. 只输出 JSON，不要 Markdown，不要代码块。
                JSON 格式：
                {
                  "reportContent": "面向学生的自然语言报告，分段但不要使用 Markdown 标题",
                  "learningSuggestions": [
                    {"title": "建议标题", "reason": "依据", "action": "具体行动", "priority": "high|medium|low"}
                  ],
                  "nextRecommendation": {"focus": "下一步重点", "actionType": "review|practice|learn", "description": "说明"}
                }
                输入 JSON：
                %s
                """.formatted(toJson(input));
    }

    private EvaluationLearningReport applyReport(EvaluationLearningReport target, SessionReportVO report) {
        LocalDateTime now = LocalDateTime.now();
        target.setStatus(STATUS_READY);
        target.setPendingCount(0);
        target.setErrorMessage(null);
        target.setReportType(REPORT_TYPE_SINGLE_TEST);
        target.setTotalScore(report.getTotalScore());
        target.setActualScore(report.getActualScore());
        target.setAccuracy(report.getAccuracy());
        target.setMasterySummary(report.getMasterySummary());
        target.setWeakPoints(report.getWeakPoints());
        target.setStrongPoints(report.getStrongPoints());
        target.setAbilityEvaluation(report.getAbilityEvaluation());
        target.setLearningSuggestions(report.getLearningSuggestions());
        target.setNextRecommendationJson(report.getNextRecommendationJson());
        target.setReportContent(report.getReportContent());
        target.setGeneratedBy(report.getGeneratedBy());
        target.setAnalysisVersion(ANALYSIS_VERSION);
        if (target.getStartedAt() == null) {
            target.setStartedAt(now);
        }
        target.setFinishedAt(now);
        target.setUpdatedAt(now);
        return target;
    }

    private void markFailed(Long sessionId, Long userId, EvaluationSession session, String message) {
        try {
            EvaluationLearningReport existing = findReport(sessionId, userId);
            EvaluationLearningReport failed = upsertStatus(existing, session, sessionId, userId,
                    List.of(), STATUS_FAILED, 0, message);
            String reportKey = EvaluationRedisConstants.sessionReportKey(sessionId, userId);
            cacheService.set(reportKey, toVO(failed, session), EvaluationRedisConstants.TTL_REPORT_GENERATING);
        } catch (Exception e) {
            log.warn("写入报告失败状态异常: sessionId={}, userId={}", sessionId, userId, e);
        }
    }

    private SessionReportVO toVO(EvaluationLearningReport report, EvaluationSession session) {
        String status = report.getStatus() != null ? report.getStatus() : STATUS_READY;
        Integer pendingCount = report.getPendingCount() != null ? report.getPendingCount() : 0;
        return SessionReportVO.builder()
                .sessionId(report.getSessionId())
                .reportId(report.getId())
                .title(session != null ? session.getTopic() : null)
                .reportType(report.getReportType())
                .status(status)
                .message(statusMessage(status, pendingCount))
                .pendingCount(pendingCount)
                .errorMessage(report.getErrorMessage())
                .totalScore(report.getTotalScore())
                .actualScore(report.getActualScore())
                .accuracy(report.getAccuracy())
                .masterySummary(report.getMasterySummary())
                .weakPoints(report.getWeakPoints())
                .strongPoints(report.getStrongPoints())
                .abilityEvaluation(report.getAbilityEvaluation())
                .learningSuggestions(report.getLearningSuggestions())
                .nextRecommendationJson(report.getNextRecommendationJson())
                .reportContent(report.getReportContent())
                .generatedBy(report.getGeneratedBy())
                .createdAt(report.getCreatedAt())
                .finishedAt(report.getFinishedAt())
                .build();
    }

    private String statusMessage(String status, int pendingCount) {
        return switch (status) {
            case STATUS_WAITING_GRADING -> pendingCount > 0
                    ? "还有 " + pendingCount + " 道题正在批阅，练习分析报告会在全部批阅完成后自动生成。"
                    : "题目批阅即将完成，练习分析报告稍后生成。";
            case STATUS_GENERATING -> "AI 正在整理你的练习分析报告，稍后再来查看。";
            case STATUS_FAILED -> "练习分析报告生成失败，请稍后重试。";
            default -> "练习分析报告已生成。";
        };
    }

    private Map<Long, EvaluationQuestion> loadQuestionMap(List<EvaluationAnswerRecord> records) {
        Set<Long> questionIds = records.stream()
                .map(EvaluationAnswerRecord::getQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(EvaluationQuestion::getId, q -> q, (a, b) -> a));
    }

    private List<String> knowledgeLabels(EvaluationQuestion question) {
        if (question == null) {
            return List.of("未标注知识点");
        }
        if (question.getKnowledgePoints() != null && !question.getKnowledgePoints().isEmpty()) {
            return question.getKnowledgePoints();
        }
        if (question.getTags() != null && !question.getTags().isEmpty()) {
            return question.getTags();
        }
        return List.of("未标注知识点");
    }

    private String abilityLabel(EvaluationQuestion question) {
        if (question == null) {
            return "综合能力";
        }
        if (question.getAbilityDimension() != null && !question.getAbilityDimension().isBlank()) {
            return question.getAbilityDimension();
        }
        if (question.getAbilityTagsJson() != null && !question.getAbilityTagsJson().isEmpty()) {
            return question.getAbilityTagsJson().get(0);
        }
        return question.getQuestionType() != null ? question.getQuestionType() : "综合能力";
    }

    private BigDecimal totalScore(List<EvaluationAnswerRecord> records) {
        Set<Long> questionIds = records.stream()
                .map(EvaluationAnswerRecord::getQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (questionIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return questionMapper.selectBatchIds(questionIds).stream()
                .map(q -> q.getScore() != null ? q.getScore() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal actualScore(List<EvaluationAnswerRecord> records) {
        return records.stream()
                .map(r -> r.getScore() != null ? r.getScore() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal accuracy(BigDecimal actual, BigDecimal total) {
        if (actual == null || total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return actual.divide(total, 4, RoundingMode.HALF_UP);
    }

    private List<Map<String, Object>> fallbackSuggestions(List<Map<String, Object>> weakPoints,
                                                          List<EvaluationAnswerRecord> records,
                                                          Map<Long, EvaluationQuestion> questionMap) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map<String, Object> weak : weakPoints.stream().limit(3).toList()) {
            String label = String.valueOf(weak.get("knowledgePoint"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", "优先巩固：" + label);
            item.put("reason", "该知识点在本次练习中的得分率偏低。");
            item.put("action", "先回看该知识点的定义、边界条件和典型例题，再完成 3-5 道同类题。");
            item.put("priority", "high");
            suggestions.add(item);
        }
        if (suggestions.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", "保持当前节奏并补充变式练习");
            item.put("reason", "本次练习没有暴露明显薄弱知识点。");
            item.put("action", "选择同主题但更高难度的题目，重点训练迁移应用和综合分析。");
            item.put("priority", "medium");
            suggestions.add(item);
        }
        long wrongCount = records.stream().filter(r -> r.getIsCorrect() != null && r.getIsCorrect() == 0).count();
        if (wrongCount > 0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", "整理错题证据");
            item.put("reason", "本次存在 " + wrongCount + " 道错题或未完全得分题。");
            item.put("action", "对每道错题写下错误原因、正确关键步骤和下次检查点。");
            item.put("priority", "medium");
            suggestions.add(item);
        }
        return suggestions;
    }

    private Map<String, Object> buildNextRecommendation(List<Map<String, Object>> weakPoints,
                                                        List<Map<String, Object>> strongPoints,
                                                        List<EvaluationAnswerRecord> records) {
        Map<String, Object> next = new LinkedHashMap<>();
        if (!weakPoints.isEmpty()) {
            next.put("focus", weakPoints.get(0).get("knowledgePoint"));
            next.put("actionType", "review");
            next.put("description", "先复盘最薄弱知识点，再进行同类题强化。");
        } else {
            next.put("focus", strongPoints.isEmpty() ? "综合能力" : strongPoints.get(0).get("knowledgePoint"));
            next.put("actionType", "practice");
            next.put("description", "继续进行更高难度或综合型练习，检验迁移应用能力。");
        }
        next.put("wrongCount", records.stream().filter(r -> r.getIsCorrect() != null && r.getIsCorrect() == 0).count());
        return next;
    }

    private String fallbackReportContent(EvaluationSession session,
                                         BigDecimal total,
                                         BigDecimal actual,
                                         List<Map<String, Object>> weakPoints,
                                         List<Map<String, Object>> strongPoints,
                                         List<Map<String, Object>> suggestions) {
        String title = session != null ? session.getTopic() : "本次练习";
        String weakText = weakPoints.isEmpty()
                ? "暂未发现特别突出的薄弱知识点。"
                : "需要优先关注：" + weakPoints.stream()
                .limit(3)
                .map(p -> String.valueOf(p.get("knowledgePoint")))
                .collect(Collectors.joining("、")) + "。";
        String strongText = strongPoints.isEmpty()
                ? "优势知识点还不够稳定，需要继续通过变式题验证。"
                : "表现较好的知识点包括：" + strongPoints.stream()
                .limit(3)
                .map(p -> String.valueOf(p.get("knowledgePoint")))
                .collect(Collectors.joining("、")) + "。";
        String action = suggestions.isEmpty() ? "建议先复盘错题，再完成同类练习。" : String.valueOf(suggestions.get(0).get("action"));
        return title + "已完成。本次得分 " + actual + "/" + total + "，正确率 "
                + accuracy(actual, total).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                + "%。\n\n" + weakText + "\n\n" + strongText + "\n\n下一步建议：" + action;
    }

    private Long firstSpaceId(List<EvaluationAnswerRecord> records) {
        return records.stream()
                .map(EvaluationAnswerRecord::getLearningSpaceId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(0L);
    }

    private String shortText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String stripJsonFence(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static class KnowledgeStats {
        private final String label;
        private int totalCount;
        private int correctCount;
        private BigDecimal totalScore = BigDecimal.ZERO;
        private BigDecimal maxScore = BigDecimal.ZERO;

        private KnowledgeStats(String label) {
            this.label = label;
        }

        private void accept(EvaluationAnswerRecord record, EvaluationQuestion question) {
            totalCount++;
            if (record.getIsCorrect() != null && record.getIsCorrect() == 1) {
                correctCount++;
            }
            totalScore = totalScore.add(record.getScore() != null ? record.getScore() : BigDecimal.ZERO);
            maxScore = maxScore.add(question.getScore() != null ? question.getScore() : BigDecimal.ZERO);
        }

        private BigDecimal scoreRate() {
            if (maxScore.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return totalScore.divide(maxScore, 4, RoundingMode.HALF_UP);
        }

        private BigDecimal correctRate() {
            if (totalCount <= 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(correctCount).divide(BigDecimal.valueOf(totalCount), 4, RoundingMode.HALF_UP);
        }

        private Map<String, Object> toMap(String labelKey) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(labelKey, label);
            item.put("correctCount", correctCount);
            item.put("totalCount", totalCount);
            item.put("accuracy", correctRate());
            item.put("scoreRate", scoreRate());
            item.put("score", totalScore);
            item.put("maxScore", maxScore);
            item.put("masteryLevel", masteryLevel(scoreRate()));
            return item;
        }

        private String masteryLevel(BigDecimal rate) {
            if (rate.compareTo(BigDecimal.valueOf(0.85)) >= 0) {
                return "优秀";
            }
            if (rate.compareTo(BigDecimal.valueOf(0.70)) >= 0) {
                return "稳定";
            }
            if (rate.compareTo(BigDecimal.valueOf(0.55)) >= 0) {
                return "待巩固";
            }
            return "薄弱";
        }
    }
}
