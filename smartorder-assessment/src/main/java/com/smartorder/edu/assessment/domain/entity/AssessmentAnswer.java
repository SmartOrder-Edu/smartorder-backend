package com.smartorder.edu.assessment.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 旧版测评作答实体。
 *
 * @deprecated 全仓无任何引用，{@code AssessmentAnswerMapper} 也没有调用方。
 * 测评作答链路已重构到 {@code smartorder-student} 的 Evaluation 体系
 * （{@code EvaluationSession} / {@code EvaluationAnswerRecord} / {@code EvaluationQuestion}），
 * 本模块仅剩 {@code Assessment} 与 {@code AssessmentQuestion} 还被出题工具使用。
 * 本类可在确认后连同 Mapper 一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Data
@NoArgsConstructor
public class AssessmentAnswer {
    private Long id;
    private Long attemptId;
    private Long questionId;
    private String userAnswer;
    private Double score;
    private Integer isCorrect;
    private String aiFeedback;
    private Integer timeSpentSeconds;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
