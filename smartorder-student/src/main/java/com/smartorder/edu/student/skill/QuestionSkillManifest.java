package com.smartorder.edu.student.skill;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 题型 Skill 的 YAML frontmatter 元数据。
 *
 * <p>同时承载说明书（meta Skill）和题型 Skill 的字段：
 * <ul>
 *   <li>说明书独有：questionTypeMapping、allowedTools</li>
 *   <li>题型 Skill 独有：questionType、objective、defaultScore、defaultOptionCount</li>
 * </ul>
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@Data
public class QuestionSkillManifest {

    /** Skill 唯一标识，等于目录名，如 "single-choice" */
    private String name;

    /** 简短描述 */
    private String description;

    /** 触发条件说明 */
    private String whenToUse;

    /** 匹配关键词 */
    private List<String> keywords;

    /** 优先级，基础分 = priority / 100 */
    private int priority;

    /** 是否可被自动匹配（题型 Skill 统一为 false） */
    private boolean autoInvoke;

    /** 允许调用的 Agent Tool 列表（仅说明书使用） */
    private List<String> allowedTools;

    // ---- 以下为说明书（meta Skill）独有字段 ----

    /** 题型编码 → Skill 目录名的映射表（仅说明书有值） */
    private Map<String, String> questionTypeMapping;

    // ---- 以下为题型 Skill 独有字段 ----

    /** 题型编码，如 single_choice */
    private String questionType;

    /** 是否客观题 */
    private Boolean objective;

    /** 默认分值 */
    private Integer defaultScore;

    /** 默认选项数（选择题有值） */
    private Integer defaultOptionCount;
}
