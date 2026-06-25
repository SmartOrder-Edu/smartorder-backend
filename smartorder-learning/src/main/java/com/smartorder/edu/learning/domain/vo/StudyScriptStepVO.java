package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StudyScriptStepVO {
    private String id;
    private String phase;
    private String title;
    private String subtitle;
    private Integer estimatedMinutes;
    private String status;
    private String canvasType;
}
