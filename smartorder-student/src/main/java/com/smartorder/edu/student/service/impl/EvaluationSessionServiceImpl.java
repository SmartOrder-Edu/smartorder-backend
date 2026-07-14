package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.vo.SessionQuestionVO;
import com.smartorder.edu.student.domain.vo.SessionResultVO;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.domain.vo.TaskSubmitResultVO;
import com.smartorder.edu.student.domain.vo.DraftAnswerVO;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationItemMapper;
import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.mapper.EvaluationSessionMapper;
import com.smartorder.edu.student.service.IEvaluationSessionService;
import com.smartorder.edu.student.service.IEvaluationSubmitService;
import com.smartorder.edu.student.service.impl.EvaluationCacheService;
import com.smartorder.edu.student.util.PracticeReminderUtils;
import org.springframework.data.redis.core.RedisTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final EvaluationCacheService cacheService;
    private final RedisTemplate<String, Object> redisTemplate;

    // ======================== 创建 / 开始 ========================

    @Override
    public EvaluationSession createSession(Long learningSpaceId, Long userId, String topic,
                                            Long taskId, BigDecimal totalScore, int totalQuestionCount) {
        EvaluationSession session = new EvaluationSession();
        session.setUserId(userId);
        session.setLearningSpaceId(learningSpaceId);
        session.setTopic(topic);
        session.setTaskId(taskId);
        session.setTotalScore(totalScore);
        session.setTotalQuestionCount(totalQuestionCount);
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
    public List<SessionQuestionVO> getSessionQuestions(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }

        String cacheKey = EvaluationRedisConstants.sessionQuestionsKey(sessionId);
        return cacheService.getOrLoadList(cacheKey, EvaluationRedisConstants.TTL_SESSION_QUESTIONS,
                () -> {
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
                    return questionMapper.selectBatchIds(questionIds).stream()
                            .map(SessionQuestionVO::from)
                            .collect(Collectors.toList());
                });
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

        // 提交后清除结果和报告缓存（判分结果已变化）
        invalidateResultAndReportCache(sessionId, userId);

        log.info("测评提交完成: sessionId={}, totalScore={}, actualScore={}, accuracy={}, subjectivePending={}",
                sessionId, submitResult.getTotalScore(), submitResult.getActualScore(),
                submitResult.getAccuracy(), submitResult.getSubjectivePending());

        // 3. 转换为 SessionResultVO
        return toSessionResultVO(sessionId, submitResult);
    }

    // ======================== 保存进度（草稿） ========================

    @Override
    public void saveProgress(Long sessionId, Long userId, SubmitAnswersDTO dto) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }
        if (!"doing".equals(session.getStatus()) && !"todo".equals(session.getStatus())) {
            throw new BusinessException("测评任务状态不允许保存: " + session.getStatus());
        }

        List<SubmitAnswersDTO.AnswerItem> items = dto.getAnswers();
        if (items == null || items.isEmpty()) {
            return;
        }

        // 写入 Redis Hash：evaluation:session:{sessionId}:draft:{userId}
        String draftKey = EvaluationRedisConstants.sessionDraftKey(sessionId, userId);
        for (SubmitAnswersDTO.AnswerItem item : items) {
            Map<String, Object> draftData = new HashMap<>();
            draftData.put("userAnswer", item.getUserAnswer());
            draftData.put("duration", item.getAnswerDurationSeconds() != null ? item.getAnswerDurationSeconds() : 0);
            draftData.put("usedHint", item.getUsedHint() != null ? item.getUsedHint() : 0);
            draftData.put("updatedAt", LocalDateTime.now().toString());
            cacheService.hashPut(draftKey, item.getQuestionId().toString(), draftData);
        }
        cacheService.expire(draftKey, EvaluationRedisConstants.TTL_DRAFT);

        // 标记该 session 有待同步的草稿
        String syncFlagKey = EvaluationRedisConstants.sessionDraftSyncFlagKey(sessionId);
        redisTemplate.opsForSet().add(DraftAnswerSyncScheduler.PENDING_SYNC_SET, sessionId.toString());
        redisTemplate.expire(syncFlagKey, EvaluationRedisConstants.TTL_DRAFT);

        // 同时写 MySQL（双写，确保数据不丢）
        List<EvaluationAnswerRecord> existingRecords = answerRecordMapper.selectList(
                new LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, sessionId)
                        .eq(EvaluationAnswerRecord::getUserId, userId));

        Map<Long, EvaluationAnswerRecord> existingMap = new HashMap<>();
        for (EvaluationAnswerRecord r : existingRecords) {
            existingMap.put(r.getQuestionId(), r);
        }

        int newCount = 0, updatedCount = 0;
        for (SubmitAnswersDTO.AnswerItem item : items) {
            EvaluationAnswerRecord existing = existingMap.get(item.getQuestionId());
            if (existing != null) {
                existing.setUserAnswer(item.getUserAnswer());
                existing.setDuration(item.getAnswerDurationSeconds() != null ? item.getAnswerDurationSeconds() : 0);
                existing.setUsedHint(item.getUsedHint() != null ? item.getUsedHint() : 0);
                existing.setUpdatedAt(LocalDateTime.now());
                answerRecordMapper.updateById(existing);
                updatedCount++;
            } else {
                EvaluationAnswerRecord record = new EvaluationAnswerRecord();
                record.setSessionId(sessionId);
                record.setTaskId(sessionId);
                record.setUserId(userId);
                record.setLearningSpaceId(session.getLearningSpaceId());
                record.setQuestionId(item.getQuestionId());
                record.setUserAnswer(item.getUserAnswer());
                record.setDuration(item.getAnswerDurationSeconds() != null ? item.getAnswerDurationSeconds() : 0);
                record.setUsedHint(item.getUsedHint() != null ? item.getUsedHint() : 0);
                record.setStatus(1);
                record.setCreatedAt(LocalDateTime.now());
                record.setUpdatedAt(LocalDateTime.now());
                answerRecordMapper.insert(record);
                newCount++;
            }
        }

        long totalWithAnswer = existingRecords.stream()
                .filter(r -> r.getUserAnswer() != null && !r.getUserAnswer().isEmpty())
                .count()
                + newCount;
        session.setAnsweredCount((int) totalWithAnswer);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        log.info("答题进度已保存: sessionId={}, userId={}, new={}, updated={}",
                sessionId, userId, newCount, updatedCount);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DraftAnswerVO> getDraftAnswers(Long sessionId, Long userId) {
//        EvaluationSession session = sessionMapper.selectById(sessionId);
//        if (session == null || !session.getUserId().equals(userId)) {
//            throw new BusinessException("测评任务不存在或无权访问");
//        }

        // 优先从 Redis Hash 读取
        String draftKey = EvaluationRedisConstants.sessionDraftKey(sessionId, userId);
        Map<Object, Object> redisDrafts = cacheService.hashGetAll(draftKey);
        if (!redisDrafts.isEmpty()) {
            return redisDrafts.entrySet().stream()
                    .map(entry -> {
                        Long questionId = Long.parseLong(entry.getKey().toString());
                        Map<String, Object> data = (Map<String, Object>) entry.getValue();
                        return DraftAnswerVO.builder()
                                .questionId(questionId)
                                .userAnswer((Map<String, Object>) data.get("userAnswer"))
                                .updatedAt(parseDateTime(data.get("updatedAt")))
                                .build();
                    })
                    .toList();
        }

        // Redis miss → 穿透查 MySQL
        List<EvaluationAnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, sessionId)
                        .eq(EvaluationAnswerRecord::getUserId, userId)
                        .eq(EvaluationAnswerRecord::getStatus, 1));

        return records.stream()
                .map(r -> DraftAnswerVO.builder()
                        .questionId(r.getQuestionId())
                        .userAnswer(r.getUserAnswer())
                        .updatedAt(r.getUpdatedAt())
                        .build())
                .toList();
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value instanceof LocalDateTime dt) return dt;
        if (value instanceof String s) {
            try {
                return LocalDateTime.parse(s);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ======================== 查询结果 ========================

    @Override
    public SessionResultVO getSessionResult(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }

        String cacheKey = EvaluationRedisConstants.sessionResultKey(sessionId, userId);

        // 查询最新判分结果（含缓存）
        TaskSubmitResultVO result = cacheService.getOrLoad(cacheKey,
                EvaluationRedisConstants.TTL_RESULT, TaskSubmitResultVO.class,
                () -> submitService.getResult(sessionId, userId));

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

        String cacheKey = EvaluationRedisConstants.sessionReportKey(sessionId, userId);

        // 查缓存
        SessionReportVO cached = cacheService.get(cacheKey, SessionReportVO.class);
        if (cached != null) {
            // 检查是否是"生成中"占位符
            if (cached.getMasterySummary() != null && !cached.getMasterySummary().isEmpty()) {
                return cached;
            }
            // 占位符 → 返回空报告，前端展示 loading
            if (cached.getGeneratedBy() == null || "generating".equals(cached.getGeneratedBy())) {
                return SessionReportVO.builder()
                        .sessionId(sessionId)
                        .title(session.getTopic())
                        .generatedBy("generating")
                        .build();
            }
        }

        // 获取最新判分结果
        TaskSubmitResultVO result = submitService.getResult(sessionId, userId);

        // 检查是否有待判分题目
        List<EvaluationAnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, sessionId)
                        .eq(EvaluationAnswerRecord::getUserId, userId));

        long pendingCount = records.stream()
                .filter(r -> r.getIsCorrect() == null)
                .count();
        if (pendingCount > 0) {
            // 写入"生成中"占位符
            cacheService.set(cacheKey,
                    Map.of("status", "generating", "pendingCount", pendingCount, "generatedBy", "generating"),
                    EvaluationRedisConstants.TTL_REPORT_GENERATING);
            return SessionReportVO.builder()
                    .sessionId(sessionId)
                    .title(session.getTopic())
                    .generatedBy("generating")
                    .build();
        }

        // 查询关联题目
        Map<Long, EvaluationQuestion> questionMap = loadQuestionMap(records);

        // 按知识点聚合掌握度
        Map<Long, KnowledgeStats> kpStatsMap = new LinkedHashMap<>();
        for (EvaluationAnswerRecord record : records) {
            if (record.getIsCorrect() == null) continue;

            Long kpId = 0L;
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

        SessionReportVO report = SessionReportVO.builder()
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

        // 写入缓存
        cacheService.set(cacheKey, report, EvaluationRedisConstants.TTL_REPORT);

        return report;
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

    /**
     * 提交答案后清除结果和报告缓存
     */
    private void invalidateResultAndReportCache(Long sessionId, Long userId) {
        try {
            String resultKey = EvaluationRedisConstants.sessionResultKey(sessionId, userId);
            String reportKey = EvaluationRedisConstants.sessionReportKey(sessionId, userId);
            cacheService.delete(resultKey);
            cacheService.delete(reportKey);
            log.debug("结果/报告缓存已清除: sessionId={}, userId={}", sessionId, userId);
        } catch (Exception e) {
            log.warn("清除结果/报告缓存失败（不影响主流程）: sessionId={}, userId={}", sessionId, userId, e);
        }
    }

    // ======================== 内部统计类 ========================

    private static class KnowledgeStats {
        int totalCount = 0;
        int correctCount = 0;
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal maxScore = BigDecimal.ZERO;
    }
}
