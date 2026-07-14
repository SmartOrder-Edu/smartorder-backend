package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class ResourceGenerationJobVO {
    private Long id;
    private Long spaceId;
    private Long resourceId;
    private String packageId;
    private String resourceType;
    private String title;
    private String reason;
    private String status;
    private Integer retryCount;
    private String errorMessage;
    private String traceId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
