package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.vo.EvaluationQuestionVO;
import com.smartorder.edu.student.domain.vo.SessionResultVO;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.domain.vo.TaskSubmitResultVO;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationItemMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.mapper.EvaluationSessionMapper;
import com.smartorder.edu.student.service.IEvaluationSessionService;
import com.smartorder.edu.student.service.IEvaluationSubmitService;
import com.smartorder.edu.student.util.PracticeReminderUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * 测评任务（Session）服务实现
 *
 * <p>核心职责：
 * <ol>
 *   <li>创建 / 开始测评会话</li>
 *   <li>提交答案：委托 {@link IEvaluationSubmitService} 判分 → 更新 session 总分/实际得分/正确率</li>
 *   <li>查询判分结果：轮询主观题判分进度，回写 session</li>
 *   <li>生成学习报告：按知识点聚合答题记录，计算掌握度</li>
 * </ol>
 *
 * @author 马强
 * @since 2026-07-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationSessionServiceImpl implements IEvaluationSessionService {

    private final EvaluationSessionMapper sessionMapper;
    private final IEvaluationSubmitService submitService;
    private final EvaluationAnswerRecordMapper answerRecordMapper;
    private final EvaluationQuestionMapper questionMapper;
    private final EvaluationQuestionGenerationItemMapper generationItemMapper;
    private final PracticeReminderUtils practiceReminderUtils;

    // ======================== 创建 / 开始 ========================

    @Override
    public EvaluationSession createSession(Long learningSpaceId, Long userId, String topic,
                                            Long taskId, BigDecimal totalScore) {
        EvaluationSession session = new EvaluationSession();
        session.setUserId(userId);
        session.setLearningSpaceId(learningSpaceId);
        session.setTopic(topic);
        session.setTaskId(taskId);
        session.setTotalScore(totalScore);
        session.setStatus("todo");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        log.info("创建测评任务: sessionId={}, topic={}", session.getId(), topic);
        return session;
    }

    @Override
    public void startSession(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        log.info("sessionId={}, userId={} 开始答题", sessionId, userId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }
        if (!"todo".equals(session.getStatus())) {
            throw new BusinessException("测评任务状态不允许开始: " + session.getStatus());
        }
        session.setStatus("doing");
        session.setStartedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        log.info("开始答题: sessionId={}, userId={}", sessionId, userId);

        // 注册刷题提醒
        practiceReminderUtils.schedule(sessionId, userId);
    }

    // ======================== 试卷题目 ========================

    @Override
    public List<EvaluationQuestionVO> getSessionQuestions(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }
        // 通过 taskId 反查 generation_item 获取关联的题目ID
        Long taskId = session.getTaskId();
        if (taskId == null) {
            return List.of();
        }
        List<EvaluationQuestionGenerationItem> items = generationItemMapper.selectList(
                new LambdaQueryWrapper<EvaluationQuestionGenerationItem>()
                        .eq(EvaluationQuestionGenerationItem::getTaskId, taskId)
                        .eq(EvaluationQuestionGenerationItem::getStatus, "success")
                        .isNotNull(EvaluationQuestionGenerationItem::getQuestionId));
        List<Long> questionIds = items.stream()
                .map(EvaluationQuestionGenerationItem::getQuestionId)
                .distinct()
                .toList();
        if (questionIds.isEmpty()) {
            return List.of();
        }
        List<EvaluationQuestion> questions = questionMapper.selectBatchIds(questionIds);
        return questions.stream()
                .map(EvaluationQuestionVO::from)
                .toList();
    }

    // ======================== 提交答案 ========================

    @Override
    public SessionResultVO submitAnswers(Long sessionId, Long userId, SubmitAnswersDTO dto) {
        // 1. 校验 session
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }
        String status = session.getStatus();
        if (!"doing".equals(status) && !"todo".equals(status)) {
            throw new BusinessException("测评任务状态不允许提交: " + status);
        }

        // 2. 委托判分引擎：客观题程序判分 + 主观题异步 AI 判分（内部已更新 session 为 finished）
        TaskSubmitResultVO submitResult = submitService.submit(
                sessionId, session.getLearningSpaceId(), userId, dto);

        // 提交完成，取消提醒
        practiceReminderUtils.cancel(sessionId, userId);

        log.info("测评提交完成: sessionId={}, totalScore={}, actualScore={}, accuracy={}, subjectivePending={}",
                sessionId, submitResult.getTotalScore(), submitResult.getActualScore(),
                submitResult.getAccuracy(), submitResult.getSubjectivePending());

        // 3. 转换为 SessionResultVO
        return toSessionResultVO(sessionId, submitResult);
    }

    // ======================== 查询结果 ========================

    @Override
    public SessionResultVO getSessionResult(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }

        // 查询最新判分结果（含已完成的主观题 AI 判分）
        TaskSubmitResultVO result = submitService.getResult(sessionId, userId);

        // 如果主观题已全部批完，且 session 的分数需要更新
        if (result.getSubjectivePending() == 0) {
            boolean needUpdate = false;

            BigDecimal newActual = result.getActualScore();
            if (session.getActualScore() == null
                    || newActual.compareTo(session.getActualScore()) != 0) {
                session.setActualScore(newActual);
                needUpdate = true;
            }

            BigDecimal newAccuracy = result.getAccuracy();
            if (session.getAccuracy() == null
                    || newAccuracy.compareTo(session.getAccuracy()) != 0) {
                session.setAccuracy(newAccuracy);
                needUpdate = true;
            }

            if (needUpdate) {
                session.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(session);
                log.info("测评分数已更新: sessionId={}, actualScore={}, accuracy={}",
                        sessionId, newActual, newAccuracy);
            }
        }

        return toSessionResultVO(sessionId, result);
    }

    // ======================== 学习报告 ========================

    @Override
    public SessionReportVO getSessionReport(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }

        // 获取最新判分结果
        TaskSubmitResultVO result = submitService.getResult(sessionId, userId);

        // 查询答题记录
        List<EvaluationAnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, sessionId)
                        .eq(EvaluationAnswerRecord::getUserId, userId));

        // 查询关联题目
        Map<Long, EvaluationQuestion> questionMap = loadQuestionMap(records);

        // 按知识点聚合掌握度（从题目的 knowledge_points 字段获取）
        Map<Long, KnowledgeStats> kpStatsMap = new LinkedHashMap<>();
        for (EvaluationAnswerRecord record : records) {
            // isCorrect 为 null 表示主观题尚未判完，跳过
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

        // 构建掌握度概览
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

        return SessionReportVO.builder()
                .sessionId(sessionId)
                .title(session.getTopic())
                .reportType(null)
                .totalScore(session.getTotalScore())
                .actualScore(result.getActualScore())
                .accuracy(result.getAccuracy())
                .masterySummary(masterySummary)
                .weakPoints(weakPoints)
                .strongPoints(strongPoints)
                .createdAt(LocalDateTime.now())
                .generatedBy("system")
                .build();
    }

    // ======================== 私有工具方法 ========================

    /**
     * TaskSubmitResultVO → SessionResultVO 转换
     */
    private SessionResultVO toSessionResultVO(Long sessionId, TaskSubmitResultVO submitResult) {
        return SessionResultVO.builder()
                .sessionId(sessionId)
                .totalScore(submitResult.getTotalScore())
                .actualScore(submitResult.getActualScore())
                .accuracy(submitResult.getAccuracy())
                .objectiveScore(submitResult.getObjectiveScore())
                .objectiveTotal(submitResult.getObjectiveTotal())
                .subjectivePending(submitResult.getSubjectivePending())
                .detail(submitResult.getDetail() != null
                        ? submitResult.getDetail().stream()
                            .map(d -> SessionResultVO.AnswerDetailVO.builder()
                                    .questionId(d.getQuestionId())
                                    .isCorrect(d.getIsCorrect())
                                    .score(d.getScore())
                                    .status(d.getStatus())
                                    .judgeResult(d.getJudgeResult())
                                    .build())
                            .toList()
                        : List.of())
                .build();
    }

    /**
     * 从答题记录中提取所有 questionId，批量查询题目，构建 Map
     */
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

    // ======================== 内部统计类 ========================

    private static class KnowledgeStats {
        int totalCount = 0;
        int correctCount = 0;
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal maxScore = BigDecimal.ZERO;
    }
}
