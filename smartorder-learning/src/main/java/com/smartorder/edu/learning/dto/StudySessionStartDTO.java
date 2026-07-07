package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StudySessionStartDTO {
    private String taskId;
    private String pathNodeId;
    private String spaceId;
    private String mode;
    private String entrySource;
    private String stepId;
}
