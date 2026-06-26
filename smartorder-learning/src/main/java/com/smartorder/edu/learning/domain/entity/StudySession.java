package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class StudySession {
    private Long id;
    private String sessionId;
    private String lessonBundleId;
    private Long userId;
    private String taskId;
    private Long pathNodeId;
    private String spaceId;
    private String mode;
    private String status;
    private String currentStepId;
    private String scriptSnapshot;
    private String currentSceneSnapshot;
    private String currentTimelineSnapshot;
    private String masterySnapshot;
    private String metadata;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
