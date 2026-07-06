package com.smartorder.edu.learning.service.media;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.LessonMediaAsset;
import com.smartorder.edu.learning.domain.vo.StudySceneVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import com.smartorder.edu.learning.mapper.LessonMediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonMediaAssetService {

    private static final int MAX_SPEECH_TEXT_LENGTH = 1200;

    private final LessonMediaAssetMapper lessonMediaAssetMapper;
    private final StudyMediaProperties mediaProperties;
    private final XfyunSuperTtsProperties ttsProperties;
    private final XfyunSuperTtsClient ttsClient;
    private final SpeechTextNormalizer textNormalizer;
    private final StaticAssetStorage assetStorage;
    private final TimelineComposer timelineComposer;

    public void enrichSession(StudySessionVO session) {
        if (!isEnabled()) {
            log.info("[LessonMedia] skipped: study.media.enabled={}, xfyun.super-tts.enabled={}",
                    mediaProperties.isEnabled(), ttsProperties.isEnabled());
            return;
        }
        if (session == null) {
            log.info("[LessonMedia] skipped: session is null");
            return;
        }
        if (session.getTimelinesByStepId() == null || session.getTimelinesByStepId().isEmpty()) {
            log.info("[LessonMedia] skipped: no timelines, bundleId={}, sessionId={}",
                    session.getLessonBundleId(), session.getSessionId());
            return;
        }
        String lessonBundleId = session.getLessonBundleId();
        if (lessonBundleId == null || lessonBundleId.isBlank()) {
            log.info("[LessonMedia] skipped: lessonBundleId is blank, sessionId={}", session.getSessionId());
            return;
        }

        session.getTimelinesByStepId().forEach((stepId, timeline) -> {
            try {
                StudySceneVO scene = session.getScenesByStepId() == null ? null : session.getScenesByStepId().get(stepId);
                enrichTimeline(lessonBundleId, scene, timeline);
            } catch (Exception e) {
                log.warn("[LessonMedia] enrich timeline failed: bundleId={}, stepId={}, reason={}",
                        lessonBundleId, stepId, e.getMessage());
            }
        });

        StudyTimelineVO currentTimeline = session.getTimelinesByStepId().get(session.getCurrentStepId());
        if (currentTimeline != null) {
            session.setCurrentTimeline(currentTimeline);
        }
    }

    private void enrichTimeline(String lessonBundleId, StudySceneVO scene, StudyTimelineVO timeline) {
        if (timeline == null || timeline.getAudioUrl() != null) {
            return;
        }

        String sceneId = resolveSceneId(scene, timeline);
        String displayText = extractDisplayText(scene);
        String speechText = trimSpeechText(extractSpeechText(scene, displayText));
        if (speechText.isBlank()) {
            log.info("[LessonMedia] skipped: speechText is blank, bundleId={}, sceneId={}", lessonBundleId, sceneId);
            return;
        }

        String textHash = hash(speechText, voiceCode(), speed(), volume(), pitch());
        LessonMediaAsset asset = selectByHash(textHash);
        if (isReusableAsset(asset)) {
            log.info("[LessonMedia] reuse asset: bundleId={}, sceneId={}, assetId={}, audioUrl={}",
                    lessonBundleId, sceneId, asset.getId(), asset.getAudioUrl());
            timelineComposer.applyAudioTimeline(timeline, asset.getAudioUrl(), asset.getDurationMs(), speechText);
            return;
        }

        asset = asset == null ? createPendingAsset(lessonBundleId, sceneId, displayText, speechText, textHash) : markGenerating(asset);
        log.info("[LessonMedia] generating asset: bundleId={}, sceneId={}, assetId={}, textLength={}",
                lessonBundleId, sceneId, asset.getId(), speechText.length());
        try {
            TtsSynthesisResult result = ttsClient.synthesize(speechText).join();
            Long durationMs = timelineComposer.durationFromPhonemeText(result.phonemeText(), speechText);
            String audioUrl = assetStorage.buildLessonSceneAudioUrl(lessonBundleId, sceneId);
            timelineComposer.applyAudioTimeline(timeline, audioUrl, durationMs, speechText);
            String vtt = timelineComposer.buildWebVtt(speechText, durationMs);
            GeneratedSpeechAsset generated = assetStorage.saveLessonSceneAsset(
                    lessonBundleId,
                    sceneId,
                    displayText,
                    speechText,
                    result,
                    durationMs,
                    vtt,
                    timeline
            );
            timeline.setAudioUrl(generated.audioUrl());

            asset.setAudioUrl(generated.audioUrl());
            asset.setScriptUrl(generated.scriptUrl());
            asset.setSubtitleUrl(generated.subtitleUrl());
            asset.setTimelineUrl(generated.timelineUrl());
            asset.setDurationMs(generated.durationMs());
            asset.setProviderSid(generated.providerSessionId());
            asset.setStatus("ready");
            asset.setErrorCode(null);
            asset.setErrorMessage(null);
            lessonMediaAssetMapper.updateById(asset);
            log.info("[LessonMedia] asset ready: bundleId={}, sceneId={}, assetId={}, audioUrl={}",
                    lessonBundleId, sceneId, asset.getId(), generated.audioUrl());
        } catch (Exception e) {
            asset.setStatus("failed");
            asset.setErrorCode("TTS_GENERATE_FAILED");
            asset.setErrorMessage(e.getMessage());
            lessonMediaAssetMapper.updateById(asset);
            log.warn("[LessonMedia] asset failed: bundleId={}, sceneId={}, assetId={}, reason={}",
                    lessonBundleId, sceneId, asset.getId(), e.getMessage());
            throw e;
        }
    }

    private boolean isEnabled() {
        return mediaProperties.isEnabled() && ttsProperties.isEnabled();
    }

    private boolean isReusableAsset(LessonMediaAsset asset) {
        if (asset == null || !"ready".equals(asset.getStatus())) {
            return false;
        }
        String audioUrl = asset.getAudioUrl();
        return audioUrl != null && (audioUrl.startsWith("http://") || audioUrl.startsWith("https://"));
    }

    private LessonMediaAsset selectByHash(String textHash) {
        return lessonMediaAssetMapper.selectOne(new QueryWrapper<LessonMediaAsset>()
                .eq("text_hash", textHash)
                .eq("voice_code", voiceCode())
                .eq("speed", speed())
                .eq("pitch", pitch())
                .orderByDesc("create_time")
                .last("LIMIT 1"));
    }

    private LessonMediaAsset createPendingAsset(String lessonBundleId,
                                                String sceneId,
                                                String displayText,
                                                String speechText,
                                                String textHash) {
        LessonMediaAsset asset = new LessonMediaAsset();
        asset.setLessonBundleId(lessonBundleId);
        asset.setSceneId(sceneId);
        asset.setProvider("xfyun");
        asset.setVoiceCode(voiceCode());
        asset.setSpeed(speed());
        asset.setVolume(volume());
        asset.setPitch(pitch());
        asset.setDisplayText(displayText);
        asset.setSpeechText(speechText);
        asset.setTextHash(textHash);
        asset.setStatus("generating");
        asset.setCreateTime(LocalDateTime.now());
        asset.setUpdateTime(LocalDateTime.now());
        lessonMediaAssetMapper.insert(asset);
        return asset;
    }

    private LessonMediaAsset markGenerating(LessonMediaAsset asset) {
        asset.setStatus("generating");
        asset.setErrorCode(null);
        asset.setErrorMessage(null);
        lessonMediaAssetMapper.updateById(asset);
        return asset;
    }

    private String extractDisplayText(StudySceneVO scene) {
        if (scene == null || scene.getContent() == null) {
            return "";
        }
        Object markdown = scene.getContent().get("markdown");
        if (markdown instanceof String text && !text.isBlank()) {
            return text;
        }
        return collectSegmentText(scene.getContent(), false);
    }

    private String extractSpeechText(StudySceneVO scene, String displayText) {
        if (scene == null || scene.getContent() == null) {
            return textNormalizer.normalize(displayText);
        }
        String segmentSpeech = collectSegmentText(scene.getContent(), true);
        if (!segmentSpeech.isBlank()) {
            return textNormalizer.normalize(segmentSpeech);
        }
        return textNormalizer.normalize(displayText);
    }

    @SuppressWarnings("unchecked")
    private String collectSegmentText(Map<String, Object> content, boolean preferCommentary) {
        Object teachingFlow = content.get("teachingFlow");
        if (!(teachingFlow instanceof Map<?, ?> flow)) {
            return "";
        }
        Object segments = flow.get("segments");
        if (!(segments instanceof List<?> list)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> segment)) {
                continue;
            }
            Object commentary = segment.get("agentCommentary");
            Object markdown = segment.get("markdown");
            if (preferCommentary && commentary instanceof String text && !text.isBlank()) {
                parts.add(text);
            } else if (markdown instanceof String text && !text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join("。", parts);
    }

    private String trimSpeechText(String speechText) {
        if (speechText == null) {
            return "";
        }
        return speechText.length() <= MAX_SPEECH_TEXT_LENGTH
                ? speechText
                : speechText.substring(0, MAX_SPEECH_TEXT_LENGTH);
    }

    private String resolveSceneId(StudySceneVO scene, StudyTimelineVO timeline) {
        if (scene != null && scene.getSceneId() != null && !scene.getSceneId().isBlank()) {
            return scene.getSceneId();
        }
        if (timeline.getSceneId() != null && !timeline.getSceneId().isBlank()) {
            return timeline.getSceneId();
        }
        return timeline.getId();
    }

    private String hash(String speechText, String voiceCode, Integer speed, Integer volume, Integer pitch) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((speechText + "|" + voiceCode + "|" + speed + "|" + volume + "|" + pitch)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("生成语音文本 hash 失败", e);
        }
    }

    private String voiceCode() {
        return ttsProperties.getVoice() == null || ttsProperties.getVoice().isBlank()
                ? "x5_lingfeiyi_flow"
                : ttsProperties.getVoice();
    }

    private Integer speed() {
        return ttsProperties.getSpeed() == null ? 50 : ttsProperties.getSpeed();
    }

    private Integer volume() {
        return ttsProperties.getVolume() == null ? 50 : ttsProperties.getVolume();
    }

    private Integer pitch() {
        return ttsProperties.getPitch() == null ? 50 : ttsProperties.getPitch();
    }
}
