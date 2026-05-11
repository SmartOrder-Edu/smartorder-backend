package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class Resource {
    private Long id;
    private Long spaceId;
    private Long pathNodeId;
    private Long taskId;
    private String title;
    private String description;
    private String resourceType;
    private String url;
    private String fileFormat;
    private Long fileSizeBytes;
    private Integer durationSeconds;
    private String coverUrl;
    private Integer isAiGenerated;
    private String source;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
