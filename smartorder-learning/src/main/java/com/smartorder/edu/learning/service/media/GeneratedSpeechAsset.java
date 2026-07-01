package com.smartorder.edu.learning.service.media;

public record GeneratedSpeechAsset(
        String audioUrl,
        String scriptUrl,
        String subtitleUrl,
        String timelineUrl,
        Long durationMs,
        String providerSessionId
) {
}
