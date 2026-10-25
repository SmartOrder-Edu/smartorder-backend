package com.smartorder.edu.student.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 旧版六维能力维度枚举。
 *
 * @deprecated 能力维度重构后，维度编码由 {@code AbilityProfileDTO} 的 {@code abilities} 键
 * 承载，旧维度到六维的映射与常量集中在
 * {@link com.smartorder.edu.student.domain.support.AbilityProfileMigration}。
 * 本枚举全仓无任何引用，仅作为维度中文名的历史副本存在。新代码请使用
 * {@code AbilityProfileDTO} 与 {@code AbilityProfileMigration}，本类可在确认后删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
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
