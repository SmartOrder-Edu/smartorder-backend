package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.algorithm.EvaluationLearningAlgorithmService;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.codejudge.CodeJudgeRequest;
import com.smartorder.edu.student.codejudge.CodeJudgeResult;
import com.smartorder.edu.student.codejudge.ICodeJudgeService;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationCodeTestCase;
import com.smartorder.edu.student.domain.entity.EvaluationLearningReport;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.domain.vo.TaskSubmitResultVO;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import com.smartorder.edu.student.mapper.EvaluationCodeTestCaseMapper;
import com.smartorder.edu.student.mapper.EvaluationLearningReportMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.config.EvaluationRabbitConfig;
import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.domain.mq.CodeGradingMessage;
import com.smartorder.edu.student.domain.mq.ReportGenerateMessage;
import com.smartorder.edu.student.domain.mq.SubjectiveGradingMessage;
import com.smartorder.edu.student.mapper.EvaluationSessionMapper;
import com.smartorder.edu.student.service.IEvaluationSubmitService;
import com.smartorder.edu.student.service.impl.EvaluationCacheService;
import com.smartorder.edu.student.util.StudentPromptFileLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 测评答案提交与判分服务实现
 *
 * <p>核心流程：
 * <ol>
 *   <li>校验参数，加载题目</li>
 *   <li>遍历每道题：客观题程序判分，主观题标记 pending</li>
 *   <li>批量写入 evaluation_answer_record</li>
 *   <li>主观题异步提交到 AI 判分线程池</li>
 *   <li>组装 TaskSubmitResultVO 返回</li>
 * </ol>
 *
 * @author 马强
 * @since 2026-07-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationSubmitServiceImpl implements IEvaluationSubmitService {

    private static final String REPORT_TYPE_SINGLE_TEST = "single_test";
    private static final String REPORT_STATUS_WAITING_GRADING = "waiting_grading";
    private static final String REPORT_STATUS_GENERATING = "generating";
    private static final String REPORT_STATUS_FAILED = "failed";
    private static final String REPORT_ANALYSIS_VERSION = "v1";

    private final EvaluationAnswerRecordMapper answerRecordMapper;
    private final EvaluationQuestionMapper questionMapper;
    private final EvaluationLearningReportMapper reportMapper;
    private final EvaluationSessionMapper sessionMapper;
    private final TransactionTemplate transactionTemplate;
    private final StudentPromptFileLoader promptFileLoader;
    private final QuestionAiService questionAiService;
    private final ObjectMapper objectMapper;
    private final ICodeJudgeService codeJudgeService;
    private final EvaluationCodeTestCaseMapper codeTestCaseMapper;
    private final RabbitTemplate rabbitTemplate;
    private final EvaluationCacheService cacheService;
    private final EvaluationLearningAlgorithmService learningAlgorithmService;

    // ======================== 主观题类型 ========================
    private static final Set<String> SUBJECTIVE_TYPES = Set.of(
            "short_answer", "scenario", "sql", "multimodal"
    );

    // ======================== prompt 路径 ========================
    private static final String GRADING_PROMPT_PATH = "prompts/subjective-grading-prompt.txt";

    // ======================== 提交判分 ========================

    @Override
    public TaskSubmitResultVO submit(Long taskId, Long spaceId, Long userId, SubmitAnswersDTO dto) {
        if (taskId == null || spaceId == null || userId == null) {
            throw new BusinessException("taskId、spaceId、userId 不能为空");
        }
        List<SubmitAnswersDTO.AnswerItem> items = dto.getAnswers();
        if (items == null || items.isEmpty()) {
            throw new BusinessException("答案列表不能为空");
        }

        // 1. 批量加载题目
        List<Long> questionIds = items.stream()
                .map(SubmitAnswersDTO.AnswerItem::getQuestionId)
                .distinct()
                .toList();

        List<EvaluationQuestion> questions = questionMapper.selectBatchIds(questionIds);
        if (questions.isEmpty()) {
            throw new BusinessException("未找到对应题目");
        }

        Map<Long, EvaluationQuestion> questionMap = new HashMap<>();
        for (EvaluationQuestion q : questions) {
            questionMap.put(q.getId(), q);
        }

        // 2. 逐题判分
        List<EvaluationAnswerRecord> records = new ArrayList<>();
        BigDecimal objectiveScore = BigDecimal.ZERO;
        BigDecimal objectiveTotal = BigDecimal.ZERO;
        int subjectivePending = 0;
        List<TaskSubmitResultVO.AnswerDetailVO> details = new ArrayList<>();

        for (SubmitAnswersDTO.AnswerItem item : items) {
            EvaluationQuestion question = questionMap.get(item.getQuestionId());
            if (question == null) {
                log.warn("题目不存在，跳过: questionId={}", item.getQuestionId());
                continue;
            }

            EvaluationAnswerRecord record = buildBaseRecord(taskId, spaceId, userId, question, item);
            QuestionScore qs = QuestionScore.fromCode(question.getQuestionType());

            if ("code".equals(question.getQuestionType())) {
                // 代码题必须先落库获得 recordId，再投递 MQ；判分完成后才生成学习算法证据。
                record.setIsCorrect(null);
                record.setScore(BigDecimal.ZERO);
                record.setJudgeResult(Map.of("status", "pending_review", "message", "代码题等待沙箱判题"));
                subjectivePending++;
                details.add(buildDetail(question.getId(), null, BigDecimal.ZERO, "pending_review", record.getUserAnswer(), record.getJudgeResult()));
            } else if (qs.isObjective()) {
                // 客观题：程序判分
                GradeResult gr = gradeObjective(question, item.getUserAnswer());
                record.setIsCorrect(gr.isCorrect ? 1 : 0);
                record.setScore(gr.score);
                record.setJudgeResult(Map.of("isCorrect", gr.isCorrect, "score", gr.score, "message", gr.message));
                if (!gr.isCorrect && gr.errorType != null) {
                    record.setErrorType(gr.errorType);
                    record.setErrorReason(gr.message);
                }
                objectiveScore = objectiveScore.add(gr.score);
                objectiveTotal = objectiveTotal.add(question.getScore() != null ? question.getScore() : qs.getDefaultScore());
                details.add(buildDetail(question.getId(), gr.isCorrect, gr.score, "graded", record.getUserAnswer(), record.getJudgeResult()));
            } else {
                // 主观题：标记 pending
                record.setIsCorrect(null);
                record.setScore(BigDecimal.ZERO);
                record.setJudgeResult(Map.of("status", "pending_review", "message", "主观题等待AI批阅"));
                subjectivePending++;
                details.add(buildDetail(question.getId(), null, BigDecimal.ZERO, "pending_review", record.getUserAnswer(), record.getJudgeResult()));
            }

            records.add(record);
        }

        // 3. 批量入库
        transactionTemplate.executeWithoutResult(status -> {
            for (EvaluationAnswerRecord record : records) {
                answerRecordMapper.insert(record);
            }
        });

        // Graded objective/synchronous answers become deterministic learning evidence.
        learningAlgorithmService.processGradedRecords(records, questionMap);

        // 4. 异步判分：通过 MQ 发送主观题判分消息（含 MQ 降级的代码题）
        List<EvaluationAnswerRecord> asyncRecords = records.stream()
                .filter(r -> r.getIsCorrect() == null)
                .toList();
        if (!asyncRecords.isEmpty()) {
            dispatchGradingToMq(asyncRecords, questionMap, taskId, userId);
        }

        // 5. 组装返回
        BigDecimal total = records.stream()
                .map(r -> questionMap.get(r.getQuestionId()))
                .filter(Objects::nonNull)
                .map(q -> q.getScore() != null ? q.getScore() : QuestionScore.fromCode(q.getQuestionType()).getDefaultScore())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 6. 标记作业已经写完了，写入 EvaluationSession 数据库
        LambdaQueryWrapper<EvaluationSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EvaluationSession::getId, taskId)
                .eq(EvaluationSession::getUserId, userId);

        EvaluationSession session = sessionMapper.selectOne(queryWrapper);
        if (session != null) {
            session.setStatus("finished");
            session.setActualScore(objectiveScore);
            session.setAccuracy(total.compareTo(BigDecimal.ZERO) > 0
                    ? objectiveScore.divide(total, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            session.setAnsweredCount(items.size());
            session.setFinishedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            log.info("测评任务已标记完成: sessionId={}, actualScore={}, accuracy={}",
                    taskId, objectiveScore, session.getAccuracy());
        } else {
            log.warn("未找到对应的 EvaluationSession: sessionId={}, 跳过标记完成", taskId);
        }

        // 6. 清除结果缓存（判分中，旧缓存已失效）
        try {
            cacheService.delete(EvaluationRedisConstants.sessionResultKey(taskId, userId));
            cacheService.delete(EvaluationRedisConstants.sessionReportKey(taskId, userId));
        } catch (Exception e) {
            log.warn("清除结果缓存失败（不影响主流程）: sessionId={}, userId={}", taskId, userId, e);
        }

        // 7. 创建报告占位并异步触发生成。报告消费者会在仍有待判分题时保持 waiting_grading。
        prepareReportAndNotify(taskId, spaceId, userId, total, objectiveScore, subjectivePending);

        // 8. 返回值给前端
        return TaskSubmitResultVO.builder()
                .taskId(taskId)
                .totalScore(total)
                .actualScore(objectiveScore)
                .accuracy(total.compareTo(BigDecimal.ZERO) > 0
                        ? objectiveScore.divide(total, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .objectiveScore(objectiveScore)
                .objectiveTotal(objectiveTotal)
                .subjectivePending(subjectivePending)
                .detail(details)
                .build();
    }

    // ======================== 查询结果（轮询用） ========================

    @Override
    public TaskSubmitResultVO getResult(Long taskId, Long userId) {
        List<EvaluationAnswerRecord> records = answerRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, taskId)
                        .eq(EvaluationAnswerRecord::getUserId, userId)
        );

        if (records.isEmpty()) {
            throw new BusinessException("未找到答题记录");
        }

        // 加载题目
        List<Long> qids = records.stream().map(EvaluationAnswerRecord::getQuestionId).distinct().toList();
        List<EvaluationQuestion> questions = questionMapper.selectBatchIds(qids);
        Map<Long, EvaluationQuestion> qMap = new HashMap<>();
        for (EvaluationQuestion q : questions) {
            qMap.put(q.getId(), q);
        }

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal objScore = BigDecimal.ZERO;
        BigDecimal objTotal = BigDecimal.ZERO;
        int pending = 0;
        List<TaskSubmitResultVO.AnswerDetailVO> details = new ArrayList<>();

        for (EvaluationAnswerRecord r : records) {
            EvaluationQuestion q = qMap.get(r.getQuestionId());
            BigDecimal qScore = (q != null && q.getScore() != null) ? q.getScore() : BigDecimal.ZERO;
            total = total.add(qScore);
            actual = actual.add(r.getScore() != null ? r.getScore() : BigDecimal.ZERO);

            QuestionScore qs = q != null ? QuestionScore.fromCode(q.getQuestionType()) : QuestionScore.UNKNOWN;
            // 代码题也是确定性判题，计入客观分
            if (qs.isObjective() || "code".equals(q.getQuestionType())) {
                objScore = objScore.add(r.getScore() != null ? r.getScore() : BigDecimal.ZERO);
                objTotal = objTotal.add(qScore);
            }

            String status;
            if (r.getIsCorrect() != null) {
                status = "graded";
            } else {
                status = "pending_review";
                pending++;
            }

            details.add(buildDetail(r.getQuestionId(),
                    r.getIsCorrect() != null ? r.getIsCorrect() == 1 : null,
                    r.getScore(),
                    status,
                    r.getUserAnswer(),
                    r.getJudgeResult()));
        }

        return TaskSubmitResultVO.builder()
                .taskId(taskId)
                .totalScore(total)
                .actualScore(actual)
                .accuracy(total.compareTo(BigDecimal.ZERO) > 0
                        ? actual.divide(total, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .objectiveScore(objScore)
                .objectiveTotal(objTotal)
                .subjectivePending(pending)
                .detail(details)
                .build();
    }

    // ======================== 私有方法 ========================
    /**
     * 构建答题记录基础字段
     */
    private EvaluationAnswerRecord buildBaseRecord(Long taskId, Long spaceId, Long userId,
                                                    EvaluationQuestion question,
                                                    SubmitAnswersDTO.AnswerItem item) {
        EvaluationAnswerRecord record = new EvaluationAnswerRecord();
        record.setSessionId(taskId);
        record.setTaskId(taskId);
        record.setUserId(userId);
        record.setLearningSpaceId(spaceId);
        record.setQuestionId(question.getId());
        record.setUserAnswer(item.getUserAnswer());
        record.setCorrectAnswer(question.getCorrectAnswer());
        record.setDuration(item.getAnswerDurationSeconds() != null ? item.getAnswerDurationSeconds() : 0);
        record.setUsedHint(item.getUsedHint() != null ? item.getUsedHint() : 0);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    /**
     * 构建单题判分详情
     */
    private TaskSubmitResultVO.AnswerDetailVO buildDetail(Long questionId, Boolean isCorrect,
                                                           BigDecimal score, String status,
                                                           Map<String, Object> userAnswer,
                                                           Map<String, Object> judgeResult) {
        return TaskSubmitResultVO.AnswerDetailVO.builder()
                .questionId(questionId)
                .isCorrect(isCorrect)
                .score(score)
                .status(status)
                .userAnswer(userAnswer)
                .judgeResult(judgeResult)
                .build();
    }

    // ======================== 客观题判分 ========================

    private GradeResult gradeObjective(EvaluationQuestion question, Map<String, Object> userAnswer) {
        String type = question.getQuestionType();
        Map<String, Object> answerJson = question.getCorrectAnswer();
        BigDecimal maxScore = question.getScore() != null
                ? question.getScore()
                : QuestionScore.fromCode(type).getDefaultScore();

        if (answerJson == null) {
            return new GradeResult(false, BigDecimal.ZERO, "题目答案配置缺失", "answer_missing");
        }

        return switch (type) {
            case "single_choice" -> gradeSingleChoice(answerJson, userAnswer, maxScore);
            case "multiple_choice" -> gradeMultipleChoice(answerJson, userAnswer, maxScore);
            case "judge" -> gradeJudge(answerJson, userAnswer, maxScore);
            case "fill_blank" -> gradeFillBlank(answerJson, userAnswer, maxScore);
            default -> new GradeResult(false, BigDecimal.ZERO, "未知客观题类型: " + type, "unknown_objective");
        };
    }

    /**
     * 单选题判分：比对用户所选选项是否在 correctOptions 中
     */
    private GradeResult gradeSingleChoice(Map<String, Object> answerJson,
                                           Map<String, Object> userAnswer, BigDecimal maxScore) {
        List<String> correctOptions = extractStringList(answerJson.get("correctOptions"));
        String userSelected = extractSelectedOption(userAnswer);

        if (userSelected == null || userSelected.isEmpty()) {
            return new GradeResult(false, BigDecimal.ZERO, "未作答", "unanswered");
        }
        boolean correct = correctOptions.stream().anyMatch(opt -> normalizeOption(opt).equals(normalizeOption(userSelected)));
        return correct
                ? new GradeResult(true, maxScore, "回答正确", null)
                : new GradeResult(false, BigDecimal.ZERO, "回答错误，正确答案：" + String.join("、", correctOptions), "wrong_option");
    }

    /**
     * 多选题判分：用户所选集合必须与正确答案完全一致
     */
    private GradeResult gradeMultipleChoice(Map<String, Object> answerJson,
                                             Map<String, Object> userAnswer, BigDecimal maxScore) {
        List<String> correctOptions = extractStringList(answerJson.get("correctOptions"));
        List<String> userOptions = extractSelectedOptions(userAnswer);

        if (userOptions.isEmpty()) {
            return new GradeResult(false, BigDecimal.ZERO, "未作答", "unanswered");
        }

        Set<String> correctSet = new HashSet<>();
        for (String opt : correctOptions) correctSet.add(normalizeOption(opt));
        Set<String> userSet = new HashSet<>();
        for (String opt : userOptions) userSet.add(normalizeOption(opt));

        if (correctSet.equals(userSet)) {
            return new GradeResult(true, maxScore, "回答正确", null);
        }

        // 部分正确给一半分
        Set<String> intersection = new HashSet<>(correctSet);
        intersection.retainAll(userSet);
        if (!intersection.isEmpty() && intersection.size() < correctSet.size()) {
            BigDecimal halfScore = maxScore.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            return new GradeResult(false, halfScore,
                    "部分正确，漏选或多选。正确答案：" + String.join("、", correctOptions), "partial_correct");
        }

        return new GradeResult(false, BigDecimal.ZERO,
                "回答错误，正确答案：" + String.join("、", correctOptions), "wrong_options");
    }

    /**
     * 判断题判分：支持中文（正确/错误、对/错）和英文（true/false）比对
     */
    private GradeResult gradeJudge(Map<String, Object> answerJson,
                                    Map<String, Object> userAnswer, BigDecimal maxScore) {
        Boolean correctValue = extractBoolean(answerJson.get("correctValue"));
        if (correctValue == null) {
            return new GradeResult(false, BigDecimal.ZERO, "题目答案配置缺失", "answer_missing");
        }

        Object raw = userAnswer != null ? userAnswer.get("value") : null;
        if (raw == null) {
            return new GradeResult(false, BigDecimal.ZERO, "未作答", "unanswered");
        }

        Boolean userBool = normalizeBoolean(raw);
        if (userBool == null) {
            return new GradeResult(false, BigDecimal.ZERO, "答案格式无效", "invalid_format");
        }

        boolean correct = correctValue.equals(userBool);
        return correct
                ? new GradeResult(true, maxScore, "回答正确", null)
                : new GradeResult(false, BigDecimal.ZERO,
                    "回答错误，正确答案：" + (correctValue ? "正确" : "错误"), "wrong_judgment");
    }

    /**
     * 填空题判分：逐空比对，支持部分给分
     */
    @SuppressWarnings("unchecked")
    private GradeResult gradeFillBlank(Map<String, Object> answerJson,
                                        Map<String, Object> userAnswer, BigDecimal maxScore) {
        List<Map<String, Object>> blanks = (List<Map<String, Object>>) answerJson.get("blanks");
        if (blanks == null || blanks.isEmpty()) {
            return new GradeResult(false, BigDecimal.ZERO, "题目答案配置缺失", "answer_missing");
        }

        List<String> userBlanks = extractStringList(userAnswer != null ? userAnswer.get("blanks") : null);
        if (userBlanks == null) userBlanks = List.of();

        int total = blanks.size();
        int correctCount = 0;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < total; i++) {
            Map<String, Object> blankDef = blanks.get(i);
            List<String> acceptedAnswers = extractAcceptedAnswers(blankDef);
            String userVal = i < userBlanks.size() ? userBlanks.get(i) : "";

            if (userVal == null || userVal.trim().isEmpty()) {
                sb.append("第").append(i + 1).append("空未作答；");
                continue;
            }

            boolean matched = acceptedAnswers.stream()
                    .anyMatch(a -> a.trim().equalsIgnoreCase(userVal.trim()));
            if (matched) {
                correctCount++;
            } else {
                sb.append("第").append(i + 1).append("空错误，正确答案：")
                        .append(String.join(" 或 ", acceptedAnswers)).append("；");
            }
        }

        if (correctCount == total) {
            return new GradeResult(true, maxScore, "回答正确", null);
        }
        if (correctCount == 0) {
            return new GradeResult(false, BigDecimal.ZERO, sb.toString(), "all_wrong");
        }
        // 部分给分
        BigDecimal perBlank = maxScore.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal partial = perBlank.multiply(BigDecimal.valueOf(correctCount));
        return new GradeResult(false, partial, sb.toString(), "partial_correct");
    }

    // ======================== MQ 判分分发 ========================

    /**
     * 将待判分记录通过 MQ 发送到对应的消费者。
     * 主观题 → subjective queue，代码题 → code queue。
     * MQ 不可用时降级为同步执行。
     */
    private void dispatchGradingToMq(List<EvaluationAnswerRecord> records,
                                      Map<Long, EvaluationQuestion> questionMap,
                                      Long taskId, Long userId) {
        for (EvaluationAnswerRecord record : records) {
            EvaluationQuestion question = questionMap.get(record.getQuestionId());
            if (question == null) continue;

            // 代码题：记录已落库，此时 recordId 可用；MQ 失败时同步判题兜底。
            if ("code".equals(question.getQuestionType())) {
                try {
                    String language = extractString(record.getUserAnswer(), "language", "cpp17");
                    String sourceCode = extractString(record.getUserAnswer(), "code", "");
                    rabbitTemplate.convertAndSend(
                            EvaluationRabbitConfig.GRADING_EXCHANGE,
                            EvaluationRabbitConfig.RK_CODE,
                            new CodeGradingMessage(record.getId(), question.getId(),
                                    taskId, userId, language, sourceCode));
                    log.debug("代码题判分消息已发送: recordId={}, questionId={}", record.getId(), question.getId());
                } catch (Exception e) {
                    log.warn("代码题 MQ 发送失败，降级为同步判题: recordId={}", record.getId(), e);
                    try {
                        gradeCodeAndUpdate(record, question);
                    } catch (Exception ex) {
                        log.error("代码题同步判分失败: recordId={}", record.getId(), ex);
                        markRecordError(record, "判分异常: " + ex.getMessage());
                    }
                }
                continue;
            }

            // 主观题：发送 MQ 消息
            try {
                rabbitTemplate.convertAndSend(
                        EvaluationRabbitConfig.GRADING_EXCHANGE,
                        EvaluationRabbitConfig.RK_SUBJECTIVE,
                        new SubjectiveGradingMessage(record.getId(), question.getId(),
                                taskId, userId));
                log.debug("主观题判分消息已发送: recordId={}, questionId={}", record.getId(), question.getId());
            } catch (Exception e) {
                log.warn("主观题 MQ 发送失败，降级为同步 AI 判分: recordId={}", record.getId(), e);
                try {
                    gradeSubjectiveByAI(record, question);
                } catch (Exception ex) {
                    log.error("主观题同步判分失败: recordId={}", record.getId(), ex);
                    markRecordError(record, "判分异常: " + ex.getMessage());
                }
            }
        }
    }

    private void markRecordError(EvaluationAnswerRecord record, String message) {
        record.setJudgeResult(Map.of("status", "error", "message", message));
        record.setIsCorrect(0);
        record.setScore(BigDecimal.ZERO);
        record.setUpdatedAt(LocalDateTime.now());
        answerRecordMapper.updateById(record);
    }

    /**
     * 代码题同步判分并更新记录（MQ 降级时使用）
     */
    private void gradeCodeAndUpdate(EvaluationAnswerRecord record, EvaluationQuestion question) {
        GradeCodeResult gcr = gradeCode(question, record.getUserAnswer());
        record.setIsCorrect(gcr.accepted ? 1 : 0);
        record.setScore(gcr.score);
        record.setJudgeResult(gcr.judgeResult);
        if (!gcr.accepted && gcr.errorType != null) {
            record.setErrorType(gcr.errorType);
            record.setErrorReason(gcr.errorReason);
        }
        record.setStatus(3);
        record.setUpdatedAt(LocalDateTime.now());
        answerRecordMapper.updateById(record);
        learningAlgorithmService.processGradedRecord(record, question);
    }

    private void prepareReportAndNotify(Long sessionId, Long spaceId, Long userId,
                                        BigDecimal totalScore, BigDecimal currentScore,
                                        int pendingCount) {
        upsertReportPlaceholder(sessionId, spaceId, userId, totalScore, currentScore, pendingCount);
        try {
            rabbitTemplate.convertAndSend(
                    EvaluationRabbitConfig.REPORT_EXCHANGE,
                    EvaluationRabbitConfig.RK_REPORT,
                    new ReportGenerateMessage(sessionId, userId));
            log.info("报告生成消息已发送: sessionId={}, userId={}, pendingCount={}",
                    sessionId, userId, pendingCount);
        } catch (Exception e) {
            log.warn("发送报告生成消息失败: sessionId={}, userId={}", sessionId, userId, e);
            if (pendingCount == 0) {
                markReportFailed(sessionId, userId, "报告生成任务投递失败: " + e.getMessage());
            }
        }
    }

    private void upsertReportPlaceholder(Long sessionId, Long spaceId, Long userId,
                                         BigDecimal totalScore, BigDecimal currentScore,
                                         int pendingCount) {
        try {
            EvaluationLearningReport report = reportMapper.selectOne(
                    new LambdaQueryWrapper<EvaluationLearningReport>()
                            .eq(EvaluationLearningReport::getSessionId, sessionId)
                            .eq(EvaluationLearningReport::getUserId, userId)
                            .eq(EvaluationLearningReport::getReportType, REPORT_TYPE_SINGLE_TEST)
                            .last("LIMIT 1"));
            LocalDateTime now = LocalDateTime.now();
            if (report == null) {
                report = new EvaluationLearningReport();
                report.setUserId(userId);
                report.setLearningSpaceId(spaceId);
                report.setSessionId(sessionId);
                report.setReportType(REPORT_TYPE_SINGLE_TEST);
                report.setCreatedAt(now);
                report.setRetryCount(0);
            }
            report.setStatus(pendingCount > 0 ? REPORT_STATUS_WAITING_GRADING : REPORT_STATUS_GENERATING);
            report.setPendingCount(pendingCount);
            report.setErrorMessage(null);
            report.setTotalScore(totalScore);
            report.setActualScore(currentScore);
            report.setAnalysisVersion(REPORT_ANALYSIS_VERSION);
            report.setStartedAt(pendingCount > 0 ? null : now);
            report.setFinishedAt(null);
            report.setUpdatedAt(now);
            if (report.getId() == null) {
                reportMapper.insert(report);
            } else {
                reportMapper.updateById(report);
            }
        } catch (Exception e) {
            log.warn("写入报告占位失败（不影响提交）: sessionId={}, userId={}", sessionId, userId, e);
        }
    }

    private void markReportFailed(Long sessionId, Long userId, String message) {
        try {
            EvaluationLearningReport report = reportMapper.selectOne(
                    new LambdaQueryWrapper<EvaluationLearningReport>()
                            .eq(EvaluationLearningReport::getSessionId, sessionId)
                            .eq(EvaluationLearningReport::getUserId, userId)
                            .eq(EvaluationLearningReport::getReportType, REPORT_TYPE_SINGLE_TEST)
                            .last("LIMIT 1"));
            if (report == null) {
                return;
            }
            report.setStatus(REPORT_STATUS_FAILED);
            report.setErrorMessage(message);
            report.setFinishedAt(LocalDateTime.now());
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);
            cacheService.delete(EvaluationRedisConstants.sessionReportKey(sessionId, userId));
        } catch (Exception e) {
            log.warn("标记报告失败状态异常: sessionId={}, userId={}", sessionId, userId, e);
        }
    }

    private void gradeSubjectiveByAI(EvaluationAnswerRecord record, EvaluationQuestion question) {
        if (question == null) return;

        String gradingPrompt = promptFileLoader.load(GRADING_PROMPT_PATH);
        String prompt = buildGradingPrompt(gradingPrompt, question, record);
        log.info("主观题 AI 判分开始: recordId={}, questionId={}", record.getId(), question.getId());

        try {
            // 流式调用 AI，收集完整响应
            StringBuilder fullResponse = new StringBuilder();
            questionAiService.generateQuestions(prompt)
                    .doOnNext(fullResponse::append)
                    .blockLast();

            String responseJson = fullResponse.toString().trim();
            // 去掉可能的 markdown 代码块包裹
            responseJson = responseJson.replaceAll("^```json\\s*", "").replaceAll("\\s*```$", "");

            // 解析 AI 返回的 JSON
            var judgeNode = objectMapper.readTree(responseJson);
            boolean isCorrect = judgeNode.has("isCorrect") && judgeNode.get("isCorrect").asBoolean();
            BigDecimal score = judgeNode.has("score")
                    ? BigDecimal.valueOf(judgeNode.get("score").asDouble()).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            String comment = judgeNode.has("comment") ? judgeNode.get("comment").asText() : "";
            String errorType = judgeNode.has("errorType") ? judgeNode.get("errorType").asText() : null;

            record.setIsCorrect(isCorrect ? 1 : 0);
            record.setScore(score);
            if (errorType != null) record.setErrorType(errorType);
            if (!comment.isEmpty()) record.setErrorReason(comment);
            record.setJudgeResult(Map.of(
                    "isCorrect", isCorrect,
                    "score", score,
                    "comment", comment,
                    "errorType", errorType != null ? errorType : "",
                    "gradedBy", "AI"
            ));
            record.setUpdatedAt(LocalDateTime.now());
            answerRecordMapper.updateById(record);
            learningAlgorithmService.processGradedRecord(record, question);

            log.info("主观题 AI 判分完成: recordId={}, isCorrect={}, score={}", record.getId(), isCorrect, score);
        } catch (Exception e) {
            log.error("主观题 AI 判分解析失败: recordId={}", record.getId(), e);
            record.setIsCorrect(0);
            record.setScore(BigDecimal.ZERO);
            record.setJudgeResult(Map.of("status", "error", "message", "AI判分解析失败: " + e.getMessage()));
            record.setUpdatedAt(LocalDateTime.now());
            answerRecordMapper.updateById(record);
        }
    }

    private String buildGradingPrompt(String template, EvaluationQuestion question, EvaluationAnswerRecord record) {
        String userAnswer = "";
        if (record.getUserAnswer() != null) {
            Object text = record.getUserAnswer().get("text");
            userAnswer = text != null ? text.toString() : record.getUserAnswer().toString();
        }

        return template
                .replace("{{questionType}}", getQuestionTypeName(question.getQuestionType()))
                .replace("{{stem}}", question.getStem() != null ? question.getStem() : "")
                .replace("{{standardAnswer}}", formatAnswerJson(question.getCorrectAnswer()))
                .replace("{{explanation}}", question.getExplanation() != null ? question.getExplanation() : "无")
                .replace("{{maxScore}}", question.getScore() != null ? question.getScore().toString() : "10")
                .replace("{{userAnswer}}", userAnswer);
    }

    private String getQuestionTypeName(String code) {
        return switch (code) {
            case "short_answer" -> "简答题";
            case "code" -> "编程题";
            case "scenario" -> "场景题";
            case "sql" -> "SQL题";
            case "multimodal" -> "多模态题";
            default -> code;
        };
    }

    private String formatAnswerJson(Map<String, Object> answerJson) {
        if (answerJson == null) return "无";
        if (answerJson.containsKey("answer")) return String.valueOf(answerJson.get("answer"));
        if (answerJson.containsKey("referenceAnswer")) return String.valueOf(answerJson.get("referenceAnswer"));
        if (answerJson.containsKey("expectedResultDescription"))
            return String.valueOf(answerJson.get("expectedResultDescription"));
        try {
            return objectMapper.writeValueAsString(answerJson);
        } catch (Exception e) {
            return answerJson.toString();
        }
    }

    // ======================== 工具方法 ========================

    private List<String> extractStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAcceptedAnswers(Map<String, Object> blankDef) {
        if (blankDef == null) return List.of();
        Object answer = blankDef.get("answer");
        Object correctValue = blankDef.get("correctValue");
        Object val = answer != null ? answer : correctValue;
        if (val instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (val != null) return List.of(String.valueOf(val));
        return List.of();
    }

    private String extractSelectedOption(Map<String, Object> userAnswer) {
        if (userAnswer == null) return null;
        Object opts = userAnswer.get("selectedOptions");
        if (opts instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return null;
    }

    private List<String> extractSelectedOptions(Map<String, Object> userAnswer) {
        if (userAnswer == null) return List.of();
        Object opts = userAnswer.get("selectedOptions");
        if (opts instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String normalizeOption(String option) {
        if (option == null) return "";
        return option.trim().toUpperCase().replaceAll("[.、。．,，\\s]", "");
    }

    private Boolean extractBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return normalizeBoolean(s);
        return null;
    }

    private Boolean normalizeBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim();
        if ("正确".equals(s) || "对".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s))
            return true;
        if ("错误".equals(s) || "错".equals(s) || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "0".equals(s))
            return false;
        return null;
    }

    // ======================== 代码题判分 ========================

    /**
     * 代码题判分：加载测试用例，调用 Docker 判题服务
     */
    private GradeCodeResult gradeCode(EvaluationQuestion question, Map<String, Object> userAnswer) {
        // 1. 提取语言和源码
        String language = extractString(userAnswer, "language", "cpp17");
        String sourceCode = extractString(userAnswer, "code", "");

        if (sourceCode == null || sourceCode.isBlank()) {
            BigDecimal maxScore = question.getScore() != null
                    ? question.getScore()
                    : QuestionScore.CODE.getDefaultScore();
            return new GradeCodeResult(false, BigDecimal.ZERO, maxScore,
                    Map.of("status", "UNANSWERED", "message", "未提交代码"),
                    "unanswered", "未提交代码");
        }

        // 2. 加载测试用例（仅启用状态的，按排序字段升序）
        List<EvaluationCodeTestCase> testCases = codeTestCaseMapper.selectList(
                new LambdaQueryWrapper<EvaluationCodeTestCase>()
                        .eq(EvaluationCodeTestCase::getQuestionId, question.getId())
                        .eq(EvaluationCodeTestCase::getStatus, 1)
                        .orderByAsc(EvaluationCodeTestCase::getSortOrder));

        BigDecimal maxScore = question.getScore() != null
                ? question.getScore()
                : QuestionScore.CODE.getDefaultScore();

        if (testCases.isEmpty()) {
            log.warn("代码题 {} 没有配置测试用例，回退为0分", question.getId());
            return new GradeCodeResult(false, BigDecimal.ZERO, maxScore,
                    Map.of("status", "SE", "message", "题目未配置测试用例"),
                    "system_error", "题目未配置测试用例");
        }

        // 3. 从 correctAnswer 解析题目级时间/内存配置
        Integer timeLimitMs = null;
        Integer memoryLimitMb = null;
        Map<String, Object> config = question.getCorrectAnswer();
        if (config != null) {
            if (config.get("timeLimitMs") instanceof Number tl) timeLimitMs = tl.intValue();
            if (config.get("memoryLimitMb") instanceof Number ml) memoryLimitMb = ml.intValue();
        }

        // 4. 构建判题请求并执行
        CodeJudgeRequest request = CodeJudgeRequest.builder()
                .questionId(question.getId())
                .language(language)
                .sourceCode(sourceCode)
                .maxScore(maxScore)
                .testCases(testCases)
                .timeLimitMs(timeLimitMs)
                .memoryLimitMb(memoryLimitMb)
                .build();

        CodeJudgeResult result = codeJudgeService.judge(request);
        Map<String, Object> judgeJson = result.toJudgeResultJson();

        String errorType = result.getStatus() != null ? result.getStatus().name().toLowerCase() : "se";
        String errorReason = result.getCompileMessage() != null && !result.getCompileMessage().isEmpty()
                ? result.getCompileMessage()
                : result.getRuntimeMessage();

        return new GradeCodeResult(
                result.getAccepted() != null && result.getAccepted(),
                result.getScore() != null ? result.getScore() : BigDecimal.ZERO,
                result.getMaxScore() != null ? result.getMaxScore() : maxScore,
                judgeJson,
                errorType,
                errorReason);
    }

    /**
     * 从 Map 中安全提取字符串值
     */
    private String extractString(Map<String, Object> map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    // ======================== 判分结果内部类 ========================

    private record GradeResult(boolean isCorrect, BigDecimal score, String message, String errorType) {
    }

    private record GradeCodeResult(boolean accepted, BigDecimal score, BigDecimal maxScore,
                                    Map<String, Object> judgeResult, String errorType, String errorReason) {
    }
}
