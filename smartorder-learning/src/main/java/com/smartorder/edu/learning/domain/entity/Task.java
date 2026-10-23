package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
public class Task {
    private Long id;
    private Long userId;
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
    private Integer sortOrder;
    private String aiSuggestion;
    private String agentReason;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
