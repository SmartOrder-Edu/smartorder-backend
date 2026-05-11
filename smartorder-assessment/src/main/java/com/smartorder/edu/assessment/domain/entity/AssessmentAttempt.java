package com.smartorder.edu.assessment.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
