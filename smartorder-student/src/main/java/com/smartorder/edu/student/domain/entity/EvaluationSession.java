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
 * 测评Agent测评任务实体
 *
 * @author 马强
 * @since 2026-05-27
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_session", autoResultMap = true)
public class EvaluationSession {

    /** 测评任务ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 测评类型：diagnosis/chapter/weakness/stage/final/comprehensive */
    private String sessionType;

    /** 测评标题 */
    private String title;

    /** 章节ID */
    private Long chapterId;

    /** 目标知识点列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> targetKnowledgePoints;

    /** 题目ID列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> questionIds;

    /** 难度分布 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> difficultyDistribution;

    /** 能力维度分布 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityDistribution;

    /** 题目总数 */
    private Integer totalQuestionCount;

    /** 已作答数量 */
    private Integer answeredCount;

    /** 总分 */
    private BigDecimal totalScore;

    /** 实际得分 */
    private BigDecimal actualScore;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 状态：created/doing/finished/cancelled */
    private String status;

    /** 组卷策略JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> agentStrategyJson;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
