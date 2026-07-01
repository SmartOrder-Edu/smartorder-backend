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
        timeline.setDurationMs(Math.max(timeline.getDurationMs() == null ? 0L : timeline.getDurationMs(), effectiveDurationMs));

        List<StudyTimelineCueVO> cues = new ArrayList<>();
        cues.addAll(buildSubtitleCues(timeline.getSceneId(), speechText, effectiveDurationMs));
        cues.addAll(timeline.getCues());
        timeline.setCues(cues);

        Map<String, Object> metadata = timeline.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(timeline.getMetadata());
        metadata.put("clockSource", "audio");
        metadata.put("audioGenerated", true);
        metadata.put("audioDurationMs", effectiveDurationMs);
        metadata.put("subtitleSync", "sentence_estimated");
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
}
