package com.smartorder.edu.learning.service.ppt;

public interface DocmeePptClient {

    DocmeeApiToken createApiToken(String uid, Integer limit);

    DocmeeTemplateOptions getTemplateOptions(String token, String lang);

    DocmeeTemplate randomTemplate(String token, DocmeeTemplateRequest request, String lang);

    DocmeeTask createMarkdownTask(String token, String markdown);

    DocmeePptResult generatePptx(String token, String taskId, String markdown, String templateId);
}
