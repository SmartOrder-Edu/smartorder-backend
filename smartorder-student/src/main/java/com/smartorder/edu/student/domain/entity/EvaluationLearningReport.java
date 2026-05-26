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
import java.util.List;
import java.util.Map;

/**
 * 测评学习报告实体
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_learning_report", autoResultMap = true)
public class EvaluationLearningReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long learningSpaceId;

    private Long sessionId;

    private String reportType;

    private BigDecimal totalScore;

    private BigDecimal actualScore;

    private BigDecimal accuracy;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> masterySummary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> weakPoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> strongPoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityEvaluation;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> learningSuggestions;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> nextRecommendationJson;

    private String reportContent;

    private String generatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
