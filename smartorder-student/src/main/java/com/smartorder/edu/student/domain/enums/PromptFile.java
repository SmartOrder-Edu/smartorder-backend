package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PromptFile {
    SINGLE_CHOICE("single_choice", "single-choice-question-prompt.txt", true),
    MULTIPLE_CHOICE("multiple_choice", "multiple-choice-question-prompt.txt", true),
    FILL_BLANK("fill_blank", "fill-blank-question-prompt.txt", true),
    JUDGE("judge", "judge-question-prompt.txt", true),
    // todo
    SHORT_ANSWER("short_answer", "short-answer-question-prompt.txt", false),
    CODE("code", "code-question-prompt.txt", false),
    SCENARIO("scenario", "scenario-question-prompt.txt", false),
    SQL("sql", "sql-question-prompt.txt", false),
    MULTIMODAL("multimodal", "multimodal-question-prompt.txt", false),
    UNKNOWN("unknown", "unknown-question-prompt.txt", false);

    private final String code;
    private final String promptFileName;
    private final boolean objective;

    public boolean isSubjective() {
        return !objective;
    }

    public static PromptFile fromCode(String code) {
        for (PromptFile type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
