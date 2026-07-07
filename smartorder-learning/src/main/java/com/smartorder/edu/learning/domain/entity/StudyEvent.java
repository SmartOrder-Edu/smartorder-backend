package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class StudyEvent {
    private Long id;
    private String eventId;
    private String sessionId;
    private Long userId;
    private String eventType;
    private String targetType;
    private String targetId;
    private String currentStepId;
    private String currentTimelineId;
    private Long timelineTimeMs;
    private Long clientTimeMs;
    private String payload;
    private String status;
    private LocalDateTime acceptedAt;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
