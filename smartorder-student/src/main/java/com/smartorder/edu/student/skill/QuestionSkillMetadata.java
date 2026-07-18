package com.smartorder.edu.student.skill;

import java.util.List;

/**
 * 登记在 Registry 中的轻量元数据，不包含正文。
 * 正文通过 {@link QuestionSkillLoader} 按需加载。
 *
 * @param skillKey      唯一标识
 * @param questionType  题型编码（题型 Skill），说明书为 null
 * @param description   简短描述
 * @param keywords      匹配关键词
 * @param priority      优先级
 * @param autoInvoke    是否自动匹配
 * @param objective     是否客观题，说明书为 null
 * @param defaultScore  默认分值，说明书为 null
 * @param contentHash   文件内容 SHA-256，用于 Loader 缓存键
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
public record QuestionSkillMetadata(
        String skillKey,
        String questionType,
        String description,
        List<String> keywords,
        int priority,
        boolean autoInvoke,
        Boolean objective,
        Integer defaultScore,
        String contentHash
) {

    /**
     * 从解析结果构建轻量元数据。
     */
    public static QuestionSkillMetadata from(ParsedQuestionSkill parsed) {
        QuestionSkillManifest m = parsed.manifest();
        return new QuestionSkillMetadata(
                parsed.skillKey(),
                m.getQuestionType(),
                m.getDescription(),
                m.getKeywords(),
                m.getPriority(),
                m.isAutoInvoke(),
                m.getObjective(),
                m.getDefaultScore(),
                parsed.contentHash()
        );
    }
}
