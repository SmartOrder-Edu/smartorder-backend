package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
public class TaskVO {
    private Long id;
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
    private Integer actualDurationMinutes;
    private LocalDateTime completedAt;
    private String aiSuggestion;
    private String agentReason;
    private String metadata;
    private Boolean isOverdue;
    private Boolean isToday;
}
