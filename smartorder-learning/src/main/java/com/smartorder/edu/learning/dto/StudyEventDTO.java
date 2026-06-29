package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudyEventDTO {
    private String eventType;
    private String targetType;
    private String targetId;
    private Long clientTimeMs;
    private Long timelineTimeMs;
    private String currentStepId;
    private String currentTimelineId;
    private Map<String, Object> payload = new HashMap<>();
}
