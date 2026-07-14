package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ResourceVO {
    private Long id;
    private Long spaceId;
    private Long pathNodeId;
    private Long taskId;
    private String title;
    private String description;
    private String resourceType;
    private String url;
    private String fileFormat;
    private String coverUrl;
    private Boolean isAiGenerated;
    private String source;
    private String status;
    private String metadata;
    private String agentReason;
    private String knowledgePoint;
    private String studyScenario;
}
