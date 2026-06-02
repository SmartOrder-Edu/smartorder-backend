package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 题型枚举
 */
@Getter
@AllArgsConstructor
public enum QuestionTypeIsObjective {

    SINGLE_CHOICE("single_choice", "单选题", true),
    MULTIPLE_CHOICE("multiple_choice", "多选题", true),
    JUDGE("judge", "判断题", true),
    FILL_BLANK("fill_blank", "填空题", true),
    SHORT_ANSWER("short_answer", "简答题", false),
    CODE("code", "编程题", false),
    SCENARIO("scenario", "场景题", false),
    SQL("sql", "SQL题", false),
    MULTIMODAL("multimodal", "多模态题", false),
    UNKNOWN("unknown", "未知题型", false);

    /** 题型编码，与数据库存储值一致 */
    private final String code;

    /** 题型中文名 */
    private final String name;

    /** 是否客观题 */
    private final boolean objective;

    public boolean isSubjective() {
        return !objective;
    }

    public static QuestionTypeIsObjective fromCode(String code) {
        for (QuestionTypeIsObjective type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知题型: " + code);
    }
}
