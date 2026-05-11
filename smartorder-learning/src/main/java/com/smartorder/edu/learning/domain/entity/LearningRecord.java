package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LearningRecord {
    private Long id;
    private Long userId;
    private Long spaceId;
    private LocalDate recordDate;
    private Integer studyDurationMinutes;
    private Integer tasksCompleted;
    private Integer tasksPlanned;
    private Integer focusLevel;
    private Integer mood;
    private Integer selfRating;
    private Integer interruptionCount;
    private String notes;
    private String aiReflection;
    private String productiveTimeSlots;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
