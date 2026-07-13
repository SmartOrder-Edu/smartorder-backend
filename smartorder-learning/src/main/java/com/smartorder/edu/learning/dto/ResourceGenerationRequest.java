package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ResourceGenerationRequest {

    private Long spaceId;
    private Long taskId;
    private Long pathNodeId;
    private String topic;
    private String resourceType;
    private String userIntent;
    private String packageMode;
    private Integer targetMinutes;
    private Integer phaseIndex;

    /**
     * Optional internal override used by Agent tools after an LLM call.
     * Public learning APIs can leave it empty and use ResourceContentGenerator fallback.
     */
    private String generatedContent;
    private String generatedContentType;
}
