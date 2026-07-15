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
 * 测评学习评测报告实体
 *
 * @author 马强
 * @since 2026-05-27
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_learning_report", autoResultMap = true)
public class EvaluationLearningReport {

    /** 评测报告ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 测评任务ID */
    private Long sessionId;

    /** 报告类型：single_test/chapter/stage/final/comprehensive */
    private String reportType;

    /** 生成状态：waiting_grading/generating/ready/failed */
    private String status;

    /** 待批阅题目数量 */
    private Integer pendingCount;

    /** 生成失败原因 */
    private String errorMessage;

    /** 重试次数 */
    private Integer retryCount;

    /** 总分 */
    private BigDecimal totalScore;

    /** 实际得分 */
    private BigDecimal actualScore;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 知识点掌握概览 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> masterySummary;

    /** 薄弱点列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> weakPoints;

    /** 优势点列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> strongPoints;

    /** 能力维度评估 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityEvaluation;

    /** 学习建议 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> learningSuggestions;

    /** 下一步推荐JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> nextRecommendationJson;

    /** 自然语言报告内容 */
    private String reportContent;

    /** 生成方式：agent/system/manual */
    private String generatedBy;

    /** 报告生成开始时间 */
    private LocalDateTime startedAt;

    /** 报告生成完成时间 */
    private LocalDateTime finishedAt;

    /** 分析算法/Prompt 版本 */
    private String analysisVersion;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
