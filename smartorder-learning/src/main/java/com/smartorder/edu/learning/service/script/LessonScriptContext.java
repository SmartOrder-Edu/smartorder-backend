package com.smartorder.edu.learning.service.script;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LessonScriptContext {
    private Long userId;
    private String taskId;
    private String spaceId;
    private String mode;
    private String requestedStepId;
    private String taskTitle;
    private String taskDescription;
    private String taskType;
    private String spaceName;
    private String spaceType;
    private String pathNodeTitle;
    private String pathNodeDescription;
    private String pathNodeType;
    private Integer difficultyLevel;
    private Integer estimatedDurationMinutes;

    public LessonScriptContext(Long userId, String taskId, String spaceId, String mode, String requestedStepId) {
        this.userId = userId;
        this.taskId = taskId;
        this.spaceId = spaceId;
        this.mode = mode;
        this.requestedStepId = requestedStepId;
    }
}
