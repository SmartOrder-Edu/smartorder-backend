package com.smartorder.edu.learning.service.ppt;

public record PptGenerationResult(
        Long resourceId,
        String pptId,
        String fileUrl,
        String templateId,
        String markdown
) {
}
