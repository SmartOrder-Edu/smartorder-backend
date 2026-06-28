package com.smartorder.edu.learning.service.script;

import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonScriptGenerationService {

    private final LessonScriptBuilder templateBuilder;
    private final LlmLessonScriptGenerator llmLessonScriptGenerator;

    @Value("${lesson.script.generator:template}")
    private String generator;

    @Value("${lesson.script.llm.enabled:false}")
    private boolean llmEnabled;

    public StudySessionVO generate(LessonScriptContext context) {
        if (shouldTryLlm()) {
            try {
                return llmLessonScriptGenerator.generate(context);
            } catch (Exception e) {
                log.warn("[LessonScript] LLM generation failed, fallback to template: taskId={}, pathNodeTitle={}, reason={}",
                        context.getTaskId(), context.getPathNodeTitle(), e.getMessage());
            }
        }
        return templateBuilder.build(context);
    }

    private boolean shouldTryLlm() {
        String normalized = generator == null ? "template" : generator.trim().toLowerCase();
        return llmEnabled || "llm".equals(normalized) || "hybrid".equals(normalized);
    }
}
