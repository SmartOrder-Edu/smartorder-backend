package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class StudyTeachingAction {
    private Long id;
    private String actionId;
    private String eventId;
    private String sessionId;
    private Long userId;
    private String actionType;
    private String sceneType;
    private String reason;
    private String payload;
    private String status;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
