package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LearningPathNode {
    private Long id;
    private Long spaceId;
    private String title;
    private String description;
    private String nodeType;
    private Integer estimatedDurationMinutes;
    private Integer difficultyLevel;
    private Integer sortOrder;
    private Integer isMilestone;
    private String iconUrl;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
