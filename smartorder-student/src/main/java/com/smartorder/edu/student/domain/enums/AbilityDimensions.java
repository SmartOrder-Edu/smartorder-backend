package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AbilityDimensions {

    CONCEPT("concept", "概念理解"),
    APPLICATION("application", "应用能力"),
    ANALYSIS("analysis", "分析能力"),
    SOLUTION("solution", "解决问题能力"),
    EXPLANATION("explanation", "解释能力"),
    COMPREHENSION("comprehension", "综合能力");

    private final String code;
    private final String description;
}
