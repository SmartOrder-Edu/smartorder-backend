package com.smartorder.edu.student.domain.enums;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 题型分值与客观性枚举。
 *
 * <p>既定义了各题型默认分值(defaultScore)，又标记是否客观题(objective)：
 * 客观题（选择/判断/填空）可由系统自动判分，主观题（简答/编程/场景/SQL/多模态）需人工或大模型判分。</p>
 */
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

    /** 题型编码，与数据库存储值一致 */
    private final String code;
    /** 该题型默认分值（保留两位小数的 BigDecimal） */
    private final BigDecimal defaultScore;
    /** 是否客观题（true=客观题，可自动判分） */
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
