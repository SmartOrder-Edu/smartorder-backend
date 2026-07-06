package com.smartorder.edu.learning.service.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.util.AliOSSUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StaticAssetStorage {

    private final ObjectMapper objectMapper;
    private final StudyMediaProperties properties;
    private final AliOSSUtils aliOSSUtils;

    public GeneratedSpeechAsset saveLessonSceneAsset(String lessonBundleId,
                                                     String sceneId,
                                                     String displayText,
                                                     String speechText,
                                                     TtsSynthesisResult result,
                                                     Long durationMs,
                                                     String transcriptVtt,
                                                     Object timelineSnapshot) {
        try {
            String baseKey = buildLessonSceneBaseKey(lessonBundleId, sceneId);
            String audioUrl = uploadBytes(baseKey + "/audio.mp3", result.audioBytes(), "audio/mpeg");
            String subtitleUrl = uploadText(baseKey + "/transcript.vtt", transcriptVtt == null ? "" : transcriptVtt, "text/vtt; charset=utf-8");
            String scriptUrl = uploadText(baseKey + "/script.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    new LinkedHashMap<>(Map.of(
                            "sceneId", sceneId,
                            "displayText", displayText,
                            "speechText", speechText
                    ))
            ), "application/json; charset=utf-8");
            String timelineUrl = uploadText(baseKey + "/timeline.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(timelineSnapshot),
                    "application/json; charset=utf-8");

            return new GeneratedSpeechAsset(
                    audioUrl,
                    scriptUrl,
                    subtitleUrl,
                    timelineUrl,
                    durationMs,
                    result.providerSessionId()
            );
        } catch (Exception e) {
            throw new IllegalStateException("保存学习语音资源失败", e);
        }
    }

    public String buildLessonSceneAudioUrl(String lessonBundleId, String sceneId) {
        return aliOSSUtils.buildObjectUrl(buildLessonSceneBaseKey(lessonBundleId, sceneId) + "/audio.mp3");
    }

    private String uploadText(String objectKey, String text, String contentType) throws Exception {
        return uploadBytes(objectKey, text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private String uploadBytes(String objectKey, byte[] bytes, String contentType) throws Exception {
        return aliOSSUtils.uploadBytes(bytes, objectKey, contentType);
    }

    private String buildLessonSceneBaseKey(String lessonBundleId, String sceneId) {
        return trimSlashes(properties.getOssPrefix()) + "/lesson-bundles/"
                + trimSlashes(lessonBundleId) + "/" + trimSlashes(sceneId);
    }

    private String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
