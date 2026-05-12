package com.smartorder.edu.assessment.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class Assessment {
    private Long id;
    private Long spaceId;
    private Long pathNodeId;
    private String title;
    private String description;
    private String assessmentType;
    private Integer timeLimitMinutes;
    private Double totalScore;
    private Double passScore;
    private Integer shuffleQuestions;
    private Integer showResultImmediately;
    private Integer attemptLimit;
    private String status;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
