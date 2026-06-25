package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudyTeachingActionVO {
    private String actionId;
    private String actionType;
    private String sceneType;
    private String reason;
    private Map<String, Object> payload = new HashMap<>();
}
