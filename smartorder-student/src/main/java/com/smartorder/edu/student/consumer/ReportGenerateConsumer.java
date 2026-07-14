package com.smartorder.edu.student.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.student.config.EvaluationRabbitConfig;
import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.mq.ReportGenerateMessage;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习报告异步生成消费者
 *
 * <p>在每个 grading consumer 判分完成后触发。检查该 session 下所有题目是否已判完，
 * 若全部完成则聚合计算知识点掌握度并写入 Redis 缓存。</p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
@Slf4j
@Component
@RabbitListener(queues = EvaluationRabbitConfig.Q_REPORT)
@RequiredArgsConstructor
public class ReportGenerateConsumer {

    private final EvaluationAnswerRecordMapper answerRecordMapper;
    private final EvaluationQuestionMapper questionMapper;
    private final EvaluationSessionMapper sessionMapper;
    private final EvaluationCacheService cacheService;

    @RabbitHandler
    public void generateReport(ReportGenerateMessage msg) {
        log.info("收到报告生成消息: sessionId={}, userId={}", msg.sessionId(), msg.userId());

        String reportKey = EvaluationRedisConstants.sessionReportKey(msg.sessionId(), msg.userId());

        // 1. 幂等检查：Redis 已有完整报告 → 跳过
        SessionReportVO existing = cacheService.get(reportKey, SessionReportVO.class);
        if (existing != null && existing.getMasterySummary() != null
                && !existing.getMasterySummary().isEmpty()) {
            log.info("报告已存在，跳过生成: sessionId={}", msg.sessionId());
            return;
        }

        // 2. 查 answer_record，判断是否所有题都已判完
        List<EvaluationAnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, msg.sessionId())
                        .eq(EvaluationAnswerRecord::getUserId, msg.userId()));

        if (records.isEmpty()) {
            log.info("暂无答题记录，跳过报告生成: sessionId={}", msg.sessionId());
            return;
        }

        long pendingCount = records.stream()
                .filter(r -> r.getIsCorrect() == null)
                .count();
        if (pendingCount > 0) {
            log.info("仍有 {} 道题待判分，跳过报告生成: sessionId={}", pendingCount, msg.sessionId());
            // 写入占位符，前端据此展示"生成中"
            cacheService.set(reportKey,
                    Map.of("status", "generating", "pendingCount", pendingCount),
                    EvaluationRedisConstants.TTL_REPORT_GENERATING);
            return;
        }

        // 3. 聚合数据
        EvaluationSession session = sessionMapper.selectById(msg.sessionId());
        Map<Long, EvaluationQuestion> questionMap = loadQuestionMap(records);

        SessionReportVO report = buildReport(msg.sessionId(), msg.userId(), session,
                records, questionMap);

        // 4. 写 Redis
        cacheService.set(reportKey, report, EvaluationRedisConstants.TTL_REPORT);

        log.info("报告生成完成: sessionId={}, userId={}", msg.sessionId(), msg.userId());
    }

    private SessionReportVO buildReport(Long sessionId, Long userId, EvaluationSession session,
                                         List<EvaluationAnswerRecord> records,
                                         Map<Long, EvaluationQuestion> questionMap) {
        // 按知识点聚合掌握度
        Map<Long, KnowledgeStats> kpStatsMap = new LinkedHashMap<>();
        for (EvaluationAnswerRecord record : records) {
            if (record.getIsCorrect() == null) continue;

            Long kpId = 0L; // 无知识点归入 0
            KnowledgeStats stats = kpStatsMap.computeIfAbsent(kpId, k -> new KnowledgeStats());
            stats.totalCount++;
            if (record.getIsCorrect() == 1) {
                stats.correctCount++;
            }
            BigDecimal score = record.getScore();
            if (score != null) {
                stats.totalScore = stats.totalScore.add(score);
            }
            EvaluationQuestion q = questionMap.get(record.getQuestionId());
            if (q != null && q.getScore() != null) {
                stats.maxScore = stats.maxScore.add(q.getScore());
            }
        }

        Map<String, Object> masterySummary = new LinkedHashMap<>();
        List<Map<String, Object>> weakPoints = new ArrayList<>();
        List<Map<String, Object>> strongPoints = new ArrayList<>();

        for (Map.Entry<Long, KnowledgeStats> entry : kpStatsMap.entrySet()) {
            KnowledgeStats stats = entry.getValue();
            BigDecimal rate = stats.totalCount > 0
                    ? BigDecimal.valueOf(stats.correctCount)
                    .divide(BigDecimal.valueOf(stats.totalCount), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgePointId", entry.getKey());
            item.put("correctCount", stats.correctCount);
            item.put("totalCount", stats.totalCount);
            item.put("accuracy", rate);
            item.put("score", stats.totalScore);

            masterySummary.put(String.valueOf(entry.getKey()), Map.of(
                    "accuracy", rate,
                    "correctCount", stats.correctCount,
                    "totalCount", stats.totalCount
            ));

            if (rate.compareTo(BigDecimal.valueOf(0.6)) < 0) {
                weakPoints.add(item);
            } else if (rate.compareTo(BigDecimal.valueOf(0.8)) >= 0) {
                strongPoints.add(item);
            }
        }

        // 计算总分和正确率
        BigDecimal totalScore = records.stream()
                .map(r -> {
                    EvaluationQuestion q = questionMap.get(r.getQuestionId());
                    return q != null && q.getScore() != null ? q.getScore() : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualScore = records.stream()
                .map(r -> r.getScore() != null ? r.getScore() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accuracy = totalScore.compareTo(BigDecimal.ZERO) > 0
                ? actualScore.divide(totalScore, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return SessionReportVO.builder()
                .sessionId(sessionId)
                .title(session != null ? session.getTopic() : null)
                .totalScore(totalScore)
                .actualScore(actualScore)
                .accuracy(accuracy)
                .masterySummary(masterySummary)
                .weakPoints(weakPoints)
                .strongPoints(strongPoints)
                .generatedBy("mq-consumer")
                .createdAt(LocalDateTime.now())
                .build();
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

    private static class KnowledgeStats {
        int totalCount = 0;
        int correctCount = 0;
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal maxScore = BigDecimal.ZERO;
    }
}
