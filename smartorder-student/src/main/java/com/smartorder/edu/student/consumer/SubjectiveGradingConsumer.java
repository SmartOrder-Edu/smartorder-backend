package com.smartorder.edu.student.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.student.algorithm.EvaluationLearningAlgorithmService;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.config.EvaluationRabbitConfig;
import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.mq.ReportGenerateMessage;
import com.smartorder.edu.student.domain.mq.SubjectiveGradingMessage;
import com.smartorder.edu.student.domain.vo.TaskSubmitResultVO;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.impl.EvaluationCacheService;
import com.smartorder.edu.student.util.StudentPromptFileLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 主观题 AI 判分消费者
 *
 * <p>从 MQ 消费主观题判分消息，调用 LLM 进行 AI 判分，
 * 更新答题记录、Redis 结果缓存，并触发报告生成。</p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
@Slf4j
@Component
@RabbitListener(queues = EvaluationRabbitConfig.Q_SUBJECTIVE, concurrency = "2-4")
@RequiredArgsConstructor
public class SubjectiveGradingConsumer {

    private final EvaluationAnswerRecordMapper answerRecordMapper;
    private final EvaluationQuestionMapper questionMapper;
    private final QuestionAiService questionAiService;
    private final StudentPromptFileLoader promptFileLoader;
    private final ObjectMapper objectMapper;
    private final EvaluationCacheService cacheService;
    private final RabbitTemplate rabbitTemplate;
    private final EvaluationLearningAlgorithmService learningAlgorithmService;

    private static final String GRADING_PROMPT_PATH = "prompts/subjective-grading-prompt.txt";

    @RabbitHandler
    public void gradeSubjective(SubjectiveGradingMessage msg) {
        log.info("收到主观题判分消息: recordId={}, questionId={}, sessionId={}",
                msg.recordId(), msg.questionId(), msg.sessionId());

        // 1. 幂等检查：查 record 是否已判分
        EvaluationAnswerRecord record = answerRecordMapper.selectById(msg.recordId());
        if (record == null) {
            log.warn("答题记录不存在，跳过: recordId={}", msg.recordId());
            return;
        }
        if (record.getIsCorrect() != null) {
            log.info("该记录已判分，跳过（幂等）: recordId={}, isCorrect={}", msg.recordId(), record.getIsCorrect());
            return;
        }

        // 2. 加载题目
        EvaluationQuestion question = questionMapper.selectById(msg.questionId());
        if (question == null) {
            log.error("题目不存在: questionId={}", msg.questionId());
            markAsError(record, "题目不存在");
            return;
        }

        // 3. AI 判分
        try {
            gradeByAI(record, question);
            learningAlgorithmService.processGradedRecord(record, question);
        } catch (Exception e) {
            log.error("主观题 AI 判分失败: recordId={}", msg.recordId(), e);
            markAsError(record, "AI判分异常: " + e.getMessage());
        }

        // 4. 更新 Redis 结果缓存（删除旧缓存，下次查询时重新加载）
        String resultKey = EvaluationRedisConstants.sessionResultKey(msg.sessionId(), msg.userId());
        cacheService.delete(resultKey);

        // 5. 触发报告生成
        notifyReportGenerate(msg.sessionId(), msg.userId());

        log.info("主观题判分完成: recordId={}, isCorrect={}, score={}",
                msg.recordId(), record.getIsCorrect(), record.getScore());
    }

    private void gradeByAI(EvaluationAnswerRecord record, EvaluationQuestion question) throws Exception {
        String gradingPrompt = promptFileLoader.load(GRADING_PROMPT_PATH);
        String prompt = buildGradingPrompt(gradingPrompt, question, record);

        // 流式调用 AI，收集完整响应
        StringBuilder fullResponse = new StringBuilder();
        questionAiService.generateQuestions(prompt)
                .doOnNext(fullResponse::append)
                .blockLast();

        String responseJson = fullResponse.toString().trim();
        responseJson = responseJson.replaceAll("^```json\\s*", "").replaceAll("\\s*```$", "");

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
        record.setStatus(3); // 已分析
        record.setUpdatedAt(LocalDateTime.now());
        answerRecordMapper.updateById(record);
    }

    private void markAsError(EvaluationAnswerRecord record, String message) {
        record.setIsCorrect(0);
        record.setScore(BigDecimal.ZERO);
        record.setJudgeResult(Map.of("status", "error", "message", message));
        record.setUpdatedAt(LocalDateTime.now());
        answerRecordMapper.updateById(record);
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
