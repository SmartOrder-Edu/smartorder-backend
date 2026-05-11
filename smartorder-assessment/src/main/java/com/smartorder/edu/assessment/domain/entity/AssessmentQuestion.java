package com.smartorder.edu.assessment.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AssessmentQuestion {
    private Long id;
    private Long assessmentId;
    private String questionType;
    private String questionText;
    private String options;
    private String correctAnswer;
    private Double score;
    private Integer sortOrder;
    private String explanation;
    private Integer difficulty;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
