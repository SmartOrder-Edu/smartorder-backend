package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 学生单科动态画像。
 */
@Data
@NoArgsConstructor
@TableName(value = "student_subject_profile", autoResultMap = true)
public class StudentSubjectProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long subjectId;

    @Version
    private Integer profileVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningGoals;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> knowledgeMastery;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> misconceptionProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningPreference;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningBehavior;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> currentLearningState;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dimensionMetadata;

    private String profileSummary;

    private BigDecimal profileCompleteness;

    private BigDecimal profileConfidenceScore;

    private String lastUpdateReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastAssessedAt;

    private LocalDateTime nextRefreshAt;
}
