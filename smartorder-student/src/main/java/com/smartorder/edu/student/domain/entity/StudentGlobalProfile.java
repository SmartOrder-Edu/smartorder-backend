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
import java.util.List;
import java.util.Map;

/**
 * 学生全局动态画像。
 */
@Data
@NoArgsConstructor
@TableName(value = "student_global_profile", autoResultMap = true)
public class StudentGlobalProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @Version
    private Integer profileVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningBackground;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningGoals;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> subjectMasteryOverview;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> misconceptionProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningPreference;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metacognitiveProfile;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dimensionMetadata;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> activeSubjectIds;

    private String profileSummary;

    private BigDecimal profileCompleteness;

    private BigDecimal profileConfidenceScore;

    private LocalDateTime lastAggregatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
