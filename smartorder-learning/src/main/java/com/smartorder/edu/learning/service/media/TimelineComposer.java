package com.smartorder.edu.learning.service.media;

import com.smartorder.edu.learning.domain.vo.StudyTimelineCueVO;
import com.smartorder.edu.learning.domain.vo.StudyTimelineVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TimelineComposer {

    private static final Pattern PHONEME_DURATION_PATTERN = Pattern.compile(":(\\d+)");

    public void applyAudioTimeline(StudyTimelineVO timeline,
                                   String audioUrl,
                                   Long durationMs,
                                   String speechText) {
        if (timeline == null || audioUrl == null || audioUrl.isBlank()) {
            return;
        }
        long effectiveDurationMs = Math.max(durationMs == null ? estimateDurationMs(speechText) : durationMs, 800L);
        timeline.setClockSource("audio");
        timeline.setAudioUrl(audioUrl);
        timeline.setDurationMs(effectiveDurationMs);

        List<StudyTimelineCueVO> cues = new ArrayList<>();
        cues.addAll(buildSubtitleCues(timeline.getSceneId(), speechText, effectiveDurationMs));
        cues.addAll(alignLessonCuesToAudio(timeline.getCues(), effectiveDurationMs));
        timeline.setCues(cues);

        Map<String, Object> metadata = timeline.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(timeline.getMetadata());
        metadata.put("clockSource", "audio");
        metadata.put("audioGenerated", true);
        metadata.put("audioDurationMs", effectiveDurationMs);
        metadata.put("subtitleSync", "sentence_estimated");
        metadata.put("segmentSync", "speech_text_weighted");
        timeline.setMetadata(metadata);
    }

    public Long durationFromPhonemeText(String phonemeText, String speechText) {
        if (phonemeText == null || phonemeText.isBlank()) {
            return estimateDurationMs(speechText);
        }
        Matcher matcher = PHONEME_DURATION_PATTERN.matcher(phonemeText);
        long frames = 0L;
        while (matcher.find()) {
            frames += Long.parseLong(matcher.group(1));
        }
        return frames > 0 ? frames * 5L : estimateDurationMs(speechText);
    }

    public String buildWebVtt(String speechText, Long durationMs) {
        List<SentenceWindow> windows = sentenceWindows(speechText, Math.max(durationMs == null ? estimateDurationMs(speechText) : durationMs, 800L));
        StringBuilder builder = new StringBuilder("WEBVTT\n\n");
        for (SentenceWindow window : windows) {
            builder.append(formatVttTime(window.startMs()))
                    .append(" --> ")
                    .append(formatVttTime(window.endMs()))
                    .append('\n')
                    .append(window.text())
                    .append("\n\n");
        }
        return builder.toString();
    }

    private List<StudyTimelineCueVO> buildSubtitleCues(String sceneId, String speechText, long durationMs) {
        List<StudyTimelineCueVO> cues = new ArrayList<>();
        int index = 1;
        for (SentenceWindow window : sentenceWindows(speechText, durationMs)) {
            StudyTimelineCueVO cue = new StudyTimelineCueVO();
            cue.setId(sceneId + "-subtitle-" + index);
            cue.setType("subtitle");
            cue.setStartMs(window.startMs());
            cue.setEndMs(window.endMs());
            cue.setPayload(new LinkedHashMap<>(Map.of(
                    "text", window.text(),
                    "syncLevel", "sentence"
            )));
            cues.add(cue);
            index++;
        }
        return cues;
    }

    private List<StudyTimelineCueVO> alignLessonCuesToAudio(List<StudyTimelineCueVO> sourceCues, long durationMs) {
        if (sourceCues == null || sourceCues.isEmpty()) {
            return List.of();
        }

        List<StudyTimelineCueVO> cues = new ArrayList<>(sourceCues);
        List<StudyTimelineCueVO> revealCues = cues.stream()
                .filter(cue -> "segment_reveal".equals(cue.getType()))
                .sorted((a, b) -> Long.compare(value(a.getStartMs()), value(b.getStartMs())))
                .toList();
        if (revealCues.isEmpty()) {
            return cues;
        }

        List<Long> weights = revealCues.stream()
                .map(this::segmentWeight)
                .toList();
        long totalWeight = weights.stream().mapToLong(Long::longValue).sum();
        if (totalWeight <= 0) {
            totalWeight = revealCues.size();
        }

        Map<String, SegmentWindow> windowsBySegmentId = new LinkedHashMap<>();
        long cursorMs = 0L;
        for (int i = 0; i < revealCues.size(); i++) {
            StudyTimelineCueVO cue = revealCues.get(i);
            long endMs = i == revealCues.size() - 1
                    ? durationMs
                    : Math.min(durationMs, cursorMs + Math.max(500L, Math.round(durationMs * (weights.get(i) / (double) totalWeight))));
            String segmentId = text(cue.getPayload().get("segmentId"), "segment-" + i);
            windowsBySegmentId.put(segmentId, new SegmentWindow(cursorMs, endMs));
            cue.setStartMs(cursorMs);
            cue.setEndMs(endMs);
            cue.setPayload(withTiming(cue.getPayload(), cursorMs, endMs));
            cursorMs = endMs;
        }

        for (StudyTimelineCueVO cue : cues) {
            if ("segment_reveal".equals(cue.getType())) {
                continue;
            }
            String segmentId = segmentIdOf(cue);
            SegmentWindow window = windowsBySegmentId.get(segmentId);
            if (window == null) {
                continue;
            }
            if ("scene_action".equals(cue.getType())) {
                cue.setStartMs(window.startMs());
                cue.setEndMs(Math.min(window.endMs(), window.startMs() + Math.max(actionDuration(cue), 600L)));
                cue.setPayload(withActionTiming(cue.getPayload(), window.startMs(), window.endMs()));
            } else if ("pause".equals(cue.getType())) {
                cue.setStartMs(window.endMs());
                cue.setEndMs(window.endMs());
                cue.setPayload(withTiming(cue.getPayload(), window.startMs(), window.endMs()));
            }
        }

        return cues;
    }

    private long segmentWeight(StudyTimelineCueVO cue) {
        String speechText = text(cue.getPayload().get("speechText"), "");
        if (!speechText.isBlank()) {
            return Math.max(1L, speechText.length());
        }
        long start = value(cue.getStartMs());
        long end = value(cue.getEndMs());
        return Math.max(1L, end - start);
    }

    @SuppressWarnings("unchecked")
    private String segmentIdOf(StudyTimelineCueVO cue) {
        if (cue == null || cue.getPayload() == null) {
            return "";
        }
        String direct = text(cue.getPayload().get("segmentId"), "");
        if (!direct.isBlank()) {
            return direct;
        }
        String targetId = text(cue.getPayload().get("targetId"), "");
        if (!targetId.isBlank()) {
            return targetId;
        }
        Object nested = cue.getPayload().get("payload");
        if (nested instanceof Map<?, ?> map) {
            return text(map.get("segmentId"), "");
        }
        return "";
    }

    private Map<String, Object> withTiming(Map<String, Object> payload, long startMs, long endMs) {
        Map<String, Object> next = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        next.put("speechStartMs", startMs);
        next.put("speechEndMs", endMs);
        Object sceneAction = next.get("sceneAction");
        if (sceneAction instanceof Map<?, ?> actionMap) {
            Map<String, Object> action = new LinkedHashMap<>();
            actionMap.forEach((key, value) -> action.put(String.valueOf(key), value));
            next.put("sceneAction", withActionTiming(action, startMs, endMs));
        }
        return next;
    }

    private Map<String, Object> withActionTiming(Map<String, Object> payload, long startMs, long endMs) {
        Map<String, Object> next = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        next.put("startMs", startMs);
        next.put("durationMs", Math.min(Math.max(endMs - startMs, 600L), 5200L));
        Object nested = next.get("payload");
        if (nested instanceof Map<?, ?> nestedMap) {
            Map<String, Object> nestedPayload = new LinkedHashMap<>();
            nestedMap.forEach((key, value) -> nestedPayload.put(String.valueOf(key), value));
            nestedPayload.put("speechStartMs", startMs);
            nestedPayload.put("speechEndMs", endMs);
            next.put("payload", nestedPayload);
        }
        return next;
    }

    private long actionDuration(StudyTimelineCueVO cue) {
        Object rawDuration = cue.getPayload() == null ? null : cue.getPayload().get("durationMs");
        if (rawDuration instanceof Number number) {
            return number.longValue();
        }
        long start = value(cue.getStartMs());
        long end = value(cue.getEndMs());
        return Math.max(600L, end - start);
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private List<SentenceWindow> sentenceWindows(String speechText, long durationMs) {
        List<String> sentences = splitSentences(speechText);
        int totalLength = sentences.stream().mapToInt(String::length).sum();
        if (totalLength <= 0) {
            totalLength = 1;
        }

        List<SentenceWindow> windows = new ArrayList<>();
        long cursor = 0L;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            long end = i == sentences.size() - 1
                    ? durationMs
                    : cursor + Math.max(500L, Math.round(durationMs * (sentence.length() / (double) totalLength)));
            windows.add(new SentenceWindow(sentence, cursor, Math.min(end, durationMs)));
            cursor = Math.min(end, durationMs);
        }
        return windows;
    }

    private List<String> splitSentences(String speechText) {
        if (speechText == null || speechText.isBlank()) {
            return List.of("我们开始学习这一段内容。");
        }
        String[] raw = speechText.split("(?<=[。！？!?；;])");
        List<String> result = new ArrayList<>();
        for (String item : raw) {
            String sentence = item.trim();
            if (!sentence.isBlank()) {
                result.add(sentence);
            }
        }
        return result.isEmpty() ? List.of(speechText.trim()) : result;
    }

    private long estimateDurationMs(String speechText) {
        int length = speechText == null ? 0 : speechText.length();
        return Math.max(1800L, Math.min(30000L, length * 220L));
    }

    private String formatVttTime(long ms) {
        long hours = ms / 3_600_000L;
        long minutes = (ms % 3_600_000L) / 60_000L;
        long seconds = (ms % 60_000L) / 1000L;
        long millis = ms % 1000L;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private record SentenceWindow(String text, long startMs, long endMs) {
    }

    private record SegmentWindow(long startMs, long endMs) {
    }
}
