package com.smartorder.edu.student.domain.enums;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public enum QuestionScore {

    SINGLE_CHOICE("single_choice", 5, true),
    MULTIPLE_CHOICE("multiple_choice", 8, true),
    FILL_BLANK("fill_blank", 5, true),
    JUDGE("judge", 3, true),

    SHORT_ANSWER("short_answer", 10, false),
    SCENARIO("scenario", 15, false),
    SQL("sql", 10, false),
    CODE("code", 15, false),
    MULTIMODAL("multimodal", 15, false),
    UNKNOWN("unknown", 0, false);

    private final String code;
    private final BigDecimal defaultScore;
    private final boolean objective;

    QuestionScore(String code, int defaultScore, boolean objective) {
        this.code = code;
        this.defaultScore = new BigDecimal(defaultScore).setScale(2, java.math.RoundingMode.HALF_UP);
        this.objective = objective;
    }

    public boolean isSubjective() {
        return !objective;
    }

    public static QuestionScore fromCode(String code) {
        for (QuestionScore type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
