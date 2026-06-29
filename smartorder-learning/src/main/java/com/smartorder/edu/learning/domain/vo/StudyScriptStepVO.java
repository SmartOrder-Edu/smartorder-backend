package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudyScriptStepVO {
    private String id;
    private String phase;
    private String nodeType;
    private String title;
    private String subtitle;
    private Integer estimatedMinutes;
    private String status;
    private String canvasType;
    private Integer sortOrder;
    private Map<String, Object> metadata = new HashMap<>();
}
