package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudySessionVO {
    private String sessionId;
    private String lessonBundleId;
    private Long userId;
    private String taskId;
    private String pathNodeId;
    private String spaceId;
    private String mode;
    private String status;
    private String currentStepId;
    private StudySceneVO currentScene;
    private StudyTimelineVO currentTimeline;
    private List<StudyScriptStepVO> scriptSteps = new ArrayList<>();
    private Map<String, StudyTimelineVO> timelinesByStepId = new LinkedHashMap<>();
    private StudyMasteryVO mastery;
}
