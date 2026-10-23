package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AbilityDimensions {

    KNOWLEDGE_UNDERSTANDING("KNOWLEDGE_UNDERSTANDING", "知识理解力"),
    PROBLEM_ANALYSIS("PROBLEM_ANALYSIS", "问题分析力"),
    SOLUTION_DESIGN("SOLUTION_DESIGN", "方案设计力"),
    PRACTICAL_APPLICATION("PRACTICAL_APPLICATION", "实践应用力"),
    ERROR_REFLECTION("ERROR_REFLECTION", "纠错反思力"),
    TRANSFER_INNOVATION("TRANSFER_INNOVATION", "迁移创新力");

    private final String code;
    private final String description;
}
