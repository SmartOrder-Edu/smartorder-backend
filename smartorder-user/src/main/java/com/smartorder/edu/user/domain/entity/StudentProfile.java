package com.smartorder.edu.user.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class StudentProfile {
    private Long id;
    private Long userId;
    private String learningStylePreference;
    private String strengths;
    private String weaknesses;
    private Integer dailyAvailableMinutes;
    private String preferredTimeSlots;
    private Integer concentrationDurationMinutes;
    private String subjectsOfInterest;
    private String academicLevel;
    private String major;
    private String semester;
    private Double gpa;
    private Integer profileVersion;
    private LocalDateTime lastAnalyzedAt;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
