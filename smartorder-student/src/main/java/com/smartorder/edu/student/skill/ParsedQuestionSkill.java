package com.smartorder.edu.student.skill;

/**
 * 一份 SKILL.md 文件的完整解析结果。
 *
 * @param skillKey    唯一标识，等于目录名，如 "single-choice" 或 "question-generation"
 * @param manifest    YAML 元数据
 * @param body        Markdown 正文（题型 Prompt 规则或说明书流程）
 * @param contentHash 原始文件内容的 SHA-256（用于判断内容是否变化）
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
public record ParsedQuestionSkill(
        String skillKey,
        QuestionSkillManifest manifest,
        String body,
        String contentHash
) {

    /** 是否为说明书（meta Skill），以 YAML 中 name 字段为准 */
    public boolean isMeta() {
        return manifest != null && "question-generation".equals(manifest.getName());
    }

    /** 是否为题型 Skill */
    public boolean isQuestionType() {
        return !isMeta();
    }
}
