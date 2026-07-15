package com.smartorder.edu.learning.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class CreateTaskDTO {
    private Long spaceId;
    private Long pathNodeId;
    private String title;
    private String description;
    private String taskType;
    private String actionType;
    private String targetType;
    private Long targetId;
    private Integer priority;
    private String status;
    private LocalDate dueDate;
    private LocalTime dueTime;
    private Integer estimatedDurationMinutes;
    private Integer sortOrder;
    private String aiSuggestion;
    private String agentReason;
    private String metadata;
}
