package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudySessionCompleteVO {
    private String sessionId;
    private String status;
    private Integer actualMinutes;
    private Integer completedSteps;
    private Integer totalSteps;
    private String masteryLevel;
    private String nextSuggestion;
    private Map<String, Object> summary = new LinkedHashMap<>();
}
