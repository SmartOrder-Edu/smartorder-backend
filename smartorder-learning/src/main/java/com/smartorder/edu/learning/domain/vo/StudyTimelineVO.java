package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudyTimelineVO {
    private String id;
    private String sceneId;
    private String clockSource;
    private String audioUrl;
    private Long durationMs;
    private List<StudyTimelineCueVO> cues = new ArrayList<>();
    private Map<String, Object> metadata = new HashMap<>();
}
