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
}
