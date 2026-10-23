package com.smartorder.edu.student.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测评学习报告 VO
 *
 * @author 马强
 * @since 2026-07-07
 */
@Data
@Builder
public class SessionReportVO {

    private Long sessionId;
    private Long reportId;
    private String title;
    private String reportType;
    private String status;
    private String message;
    private Integer pendingCount;
    private String errorMessage;
    private BigDecimal totalScore;
    private BigDecimal actualScore;
    private BigDecimal accuracy;

    /** 知识点掌握概览 */
    private Map<String, Object> masterySummary;

    /** 薄弱点 */
    private List<Map<String, Object>> weakPoints;

    /** 优势点 */
    private List<Map<String, Object>> strongPoints;

    /** 学习建议 */
    private List<Map<String, Object>> learningSuggestions;

    /** 能力维度评估 */
    private Map<String, Object> abilityEvaluation;

    /** 下一步推荐 */
    private Map<String, Object> nextRecommendationJson;

    /** 自然语言报告 */
    private String reportContent;

    /** 生成方式 */
    private String generatedBy;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
