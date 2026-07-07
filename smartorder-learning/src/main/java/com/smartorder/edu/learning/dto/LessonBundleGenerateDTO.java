package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class LessonBundleGenerateDTO {
    private String taskId;
    private Long pathNodeId;
    private String spaceId;
    private String mode;
    private Boolean forceRegenerate;
    private String generationTrigger;
    private String generationReason;
}
