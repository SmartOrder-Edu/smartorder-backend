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

    /** 章节ID */
    private Long chapterId;

    /** 测评标题 */
    private String topic;

    /** 描述 */
    private String description;

    /** 题目总数 */
    private Integer totalQuestionCount;

    /** 已作答数量 */
    private Integer answeredCount;

    /** 关联的taskId */
    private Long taskId;

    /** 总分 */
    private BigDecimal totalScore;

    /** 实际得分 */
    private BigDecimal actualScore;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 状态：todo/doing/finished/error */
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
