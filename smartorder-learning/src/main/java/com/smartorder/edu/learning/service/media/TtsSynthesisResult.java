package com.smartorder.edu.learning.service.media;

public record TtsSynthesisResult(
        byte[] audioBytes,
        String phonemeText,
        String providerSessionId
) {
}
