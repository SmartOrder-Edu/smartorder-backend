package com.smartorder.edu.learning.service.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StaticAssetStorage {

    private final ObjectMapper objectMapper;
    private final StudyMediaProperties properties;

    public GeneratedSpeechAsset saveLessonSceneAsset(String lessonBundleId,
                                                     String sceneId,
                                                     String displayText,
                                                     String speechText,
                                                     TtsSynthesisResult result,
                                                     Long durationMs,
                                                     String transcriptVtt,
                                                     Object timelineSnapshot) {
        try {
            Path sceneDir = Path.of(properties.getRootPath(), "lesson-bundles", lessonBundleId, sceneId);
            Files.createDirectories(sceneDir);

            Path audioPath = sceneDir.resolve("audio.mp3");
            Path scriptPath = sceneDir.resolve("script.json");
            Path subtitlePath = sceneDir.resolve("transcript.vtt");
            Path timelinePath = sceneDir.resolve("timeline.json");

            saveBytesAtomically(audioPath, result.audioBytes());
            saveTextAtomically(subtitlePath, transcriptVtt == null ? "" : transcriptVtt);
            saveTextAtomically(scriptPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    new LinkedHashMap<>(Map.of(
                            "sceneId", sceneId,
                            "displayText", displayText,
                            "speechText", speechText
                    ))
            ));
            saveTextAtomically(timelinePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(timelineSnapshot));

            String baseUrl = trimRight(properties.getPublicPrefix(), "/")
                    + "/lesson-bundles/" + lessonBundleId + "/" + sceneId;
            return new GeneratedSpeechAsset(
                    baseUrl + "/audio.mp3",
                    baseUrl + "/script.json",
                    baseUrl + "/transcript.vtt",
                    baseUrl + "/timeline.json",
                    durationMs,
                    result.providerSessionId()
            );
        } catch (Exception e) {
            throw new IllegalStateException("保存学习语音资源失败", e);
        }
    }

    private void saveBytesAtomically(Path targetPath, byte[] bytes) throws Exception {
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        Files.write(tempPath, bytes == null ? new byte[0] : bytes);
        Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void saveTextAtomically(Path targetPath, String text) throws Exception {
        saveBytesAtomically(targetPath, text.getBytes(StandardCharsets.UTF_8));
    }

    private String trimRight(String value, String suffix) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value;
        while (result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }
}
