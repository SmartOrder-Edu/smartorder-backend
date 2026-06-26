package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LessonBundle {
    private Long id;
    private String bundleId;
    private Long userId;
    private String taskId;
    private Long pathNodeId;
    private String spaceId;
    private String mode;
    private String status;
    private Integer version;
    private String targetKnowledgePoints;
    private String generationContext;
    private String bundleJson;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
