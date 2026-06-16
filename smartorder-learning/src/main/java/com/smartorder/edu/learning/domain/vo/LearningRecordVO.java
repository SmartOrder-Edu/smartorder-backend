package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
public class LearningRecordVO {
    private Long id;
    private LocalDate recordDate;
    private Integer studyDurationMinutes;
    private Integer tasksCompleted;
    private Integer tasksPlanned;
    private Integer focusLevel;
    private String mood;
    private Integer selfRating;
    private String aiReflection;
}
