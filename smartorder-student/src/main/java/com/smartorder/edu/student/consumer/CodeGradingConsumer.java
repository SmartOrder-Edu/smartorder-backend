package com.smartorder.edu.student.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.student.algorithm.EvaluationLearningAlgorithmService;
import com.smartorder.edu.student.codejudge.CodeJudgeRequest;
import com.smartorder.edu.student.codejudge.CodeJudgeResult;
import com.smartorder.edu.student.codejudge.ICodeJudgeService;
import com.smartorder.edu.student.config.EvaluationRabbitConfig;
import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationCodeTestCase;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.mq.CodeGradingMessage;
import com.smartorder.edu.student.domain.mq.ReportGenerateMessage;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import com.smartorder.edu.student.mapper.EvaluationCodeTestCaseMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.impl.EvaluationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 代码题沙箱判题消费者
 *
 * <p>从 MQ 消费代码题判分消息，调用 Docker 沙箱执行判题，
 * 更新答题记录、Redis 结果缓存，并触发报告生成。</p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
@Slf4j
@Component
@RabbitListener(queues = EvaluationRabbitConfig.Q_CODE, concurrency = "2-6")
@RequiredArgsConstructor
public class CodeGradingConsumer {

    private final EvaluationAnswerRecordMapper answerRecordMapper;
    private final EvaluationQuestionMapper questionMapper;
    private final EvaluationCodeTestCaseMapper codeTestCaseMapper;
    private final ICodeJudgeService codeJudgeService;
    private final EvaluationCacheService cacheService;
    private final RabbitTemplate rabbitTemplate;
    private final EvaluationLearningAlgorithmService learningAlgorithmService;

    @RabbitHandler
    public void gradeCode(CodeGradingMessage msg) {
        log.info("收到代码题判分消息: recordId={}, questionId={}, sessionId={}, language={}",
                msg.recordId(), msg.questionId(), msg.sessionId(), msg.language());

        // 1. 幂等检查
        EvaluationAnswerRecord record = answerRecordMapper.selectById(msg.recordId());
        if (record == null) {
            log.warn("答题记录不存在，跳过: recordId={}", msg.recordId());
            return;
        }
        if (record.getIsCorrect() != null) {
            log.info("该记录已判分，跳过（幂等）: recordId={}", msg.recordId());
            return;
        }

        // 2. 加载题目
        EvaluationQuestion question = questionMapper.selectById(msg.questionId());
        if (question == null) {
            log.error("题目不存在: questionId={}", msg.questionId());
            markAsError(record, "题目不存在", BigDecimal.ZERO);
            return;
        }

        // 3. 执行判题
        try {
            executeCodeJudge(record, question, msg);
            learningAlgorithmService.processGradedRecord(record, question);
        } catch (Exception e) {
            log.error("代码题判分失败: recordId={}", msg.recordId(), e);
            markAsError(record, "判题异常: " + e.getMessage(),
                    question.getScore() != null ? question.getScore() : BigDecimal.valueOf(15));
        }

        // 4. 更新 Redis 结果缓存
        String resultKey = EvaluationRedisConstants.sessionResultKey(msg.sessionId(), msg.userId());
        cacheService.delete(resultKey);

        // 5. 触发报告生成
        notifyReportGenerate(msg.sessionId(), msg.userId());

        log.info("代码题判分完成: recordId={}, isCorrect={}, score={}",
                msg.recordId(), record.getIsCorrect(), record.getScore());
    }

    private void executeCodeJudge(EvaluationAnswerRecord record, EvaluationQuestion question,
                                   CodeGradingMessage msg) {
        String sourceCode = msg.sourceCode();
        BigDecimal maxScore = question.getScore() != null
                ? question.getScore()
                : BigDecimal.valueOf(15);

        if (sourceCode == null || sourceCode.isBlank()) {
            record.setIsCorrect(0);
            record.setScore(BigDecimal.ZERO);
            record.setJudgeResult(Map.of("status", "UNANSWERED", "message", "未提交代码"));
            record.setErrorType("unanswered");
            record.setErrorReason("未提交代码");
            record.setStatus(3);
            record.setUpdatedAt(LocalDateTime.now());
            answerRecordMapper.updateById(record);
            return;
        }

        // 加载测试用例
        List<EvaluationCodeTestCase> testCases = codeTestCaseMapper.selectList(
                new LambdaQueryWrapper<EvaluationCodeTestCase>()
                        .eq(EvaluationCodeTestCase::getQuestionId, question.getId())
                        .eq(EvaluationCodeTestCase::getStatus, 1)
                        .orderByAsc(EvaluationCodeTestCase::getSortOrder));

        if (testCases.isEmpty()) {
            log.warn("代码题 {} 没有配置测试用例", question.getId());
            record.setIsCorrect(0);
            record.setScore(BigDecimal.ZERO);
            record.setJudgeResult(Map.of("status", "SE", "message", "题目未配置测试用例"));
            record.setErrorType("system_error");
            record.setErrorReason("题目未配置测试用例");
            record.setStatus(3);
            record.setUpdatedAt(LocalDateTime.now());
            answerRecordMapper.updateById(record);
            return;
        }

        // 解析时间/内存限制
        Integer timeLimitMs = null;
        Integer memoryLimitMb = null;
        Map<String, Object> config = question.getCorrectAnswer();
        if (config != null) {
            if (config.get("timeLimitMs") instanceof Number tl) timeLimitMs = tl.intValue();
            if (config.get("memoryLimitMb") instanceof Number ml) memoryLimitMb = ml.intValue();
        }

        // 构建判题请求
        CodeJudgeRequest request = CodeJudgeRequest.builder()
                .questionId(question.getId())
                .language(msg.language())
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

        record.setIsCorrect(result.getAccepted() != null && result.getAccepted() ? 1 : 0);
        record.setScore(result.getScore() != null ? result.getScore() : BigDecimal.ZERO);
        record.setJudgeResult(judgeJson);
        record.setErrorType(errorType);
        if (errorReason != null) record.setErrorReason(errorReason);
        record.setStatus(3); // 已分析
        record.setUpdatedAt(LocalDateTime.now());
        answerRecordMapper.updateById(record);
    }

    private void markAsError(EvaluationAnswerRecord record, String message, BigDecimal maxScore) {
        record.setIsCorrect(0);
        record.setScore(BigDecimal.ZERO);
        record.setJudgeResult(Map.of("status", "error", "message", message));
        record.setUpdatedAt(LocalDateTime.now());
        answerRecordMapper.updateById(record);
    }

    private void notifyReportGenerate(Long sessionId, Long userId) {
        try {
            rabbitTemplate.convertAndSend(
                    EvaluationRabbitConfig.REPORT_EXCHANGE,
                    EvaluationRabbitConfig.RK_REPORT,
                    new ReportGenerateMessage(sessionId, userId));
        } catch (Exception e) {
            log.warn("发送报告生成消息失败: sessionId={}, userId={}", sessionId, userId, e);
        }
    }
}
