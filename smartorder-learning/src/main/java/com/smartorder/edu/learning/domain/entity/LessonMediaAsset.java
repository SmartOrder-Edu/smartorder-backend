package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LessonMediaAsset {
    private Long id;
    private String lessonBundleId;
    private String sceneId;
    private String segmentId;
    private String provider;
    private String providerSid;
    private String voiceCode;
    private Integer speed;
    private Integer volume;
    private Integer pitch;
    private String displayText;
    private String speechText;
    private String textHash;
    private String audioUrl;
    private String scriptUrl;
    private String subtitleUrl;
    private String timelineUrl;
    private Long durationMs;
    private String status;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
