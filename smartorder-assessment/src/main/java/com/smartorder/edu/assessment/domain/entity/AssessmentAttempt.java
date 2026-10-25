package com.smartorder.edu.assessment.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 旧版测评作答轮次实体。
 *
 * @deprecated 全仓无任何引用，{@code AssessmentAttemptMapper} 也没有调用方。
 * 作答轮次已由 {@code smartorder-student} 的 {@code EvaluationSession} 承载，
 * 评分与报告由 {@code EvaluationAnswerRecord}、{@code EvaluationLearningReport} 负责。
 * 本类可在确认后连同 Mapper 一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Data
@NoArgsConstructor
public class AssessmentAttempt {
    private Long id;
    private Long userId;
    private Long assessmentId;
    private Integer attemptNumber;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer timeSpentSeconds;
    private Double totalScore;
    private Double maxScore;
    private String status;
    private String gradedBy;
    private String aiFeedback;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
