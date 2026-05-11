package com.smartorder.edu.assessment.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
