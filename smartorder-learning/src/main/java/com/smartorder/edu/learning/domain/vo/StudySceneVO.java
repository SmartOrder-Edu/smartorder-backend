package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudySceneVO {
    private String sceneId;
    private String sceneType;
    private String stepId;
    private Map<String, Object> content = new HashMap<>();
}
