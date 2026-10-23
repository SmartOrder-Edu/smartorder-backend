package com.smartorder.edu.learning.service.ppt;

public record PptOutlineContext(
        String spaceName,
        String sourceTitle,
        String topic,
        String sourceContent,
        String userIntent,
        Integer targetSlides
) {
}
