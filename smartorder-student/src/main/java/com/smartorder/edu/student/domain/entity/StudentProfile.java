package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 学生画像实体 — JSON 维度画像表
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
@Data
@NoArgsConstructor
@TableName(value = "student_profile", autoResultMap = true)
public class StudentProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** NULL = 全局画像（不绑定具体科目） */
    private Long subjectId;

    private Integer profileVersion;

    // ====== 完整画像快照 ======

    /** 存放所有的画像 JSON 字段 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> fullProfileSnapshot;

    // ====== 8 个 JSON 画像维度 ======

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> basicProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> knowledgeProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> preferenceProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> cognitiveProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> weaknessProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> behaviorProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> emotionProfile;

    /** 画像自然语言摘要 */
    private String profileSummary;

    /** 画像整体置信度 0-100 */
    private BigDecimal confidenceScore;

    /** 最近一次更新原因 */
    private String lastUpdateReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
