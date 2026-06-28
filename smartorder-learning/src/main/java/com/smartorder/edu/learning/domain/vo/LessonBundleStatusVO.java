package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LessonBundleStatusVO {
    private String bundleId;
    private String taskId;
    private Long pathNodeId;
    private String spaceId;
    private String mode;
    private String status;
    private Integer version;
    private String generationTrigger;
    private String generationReason;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String errorMessage;
}
