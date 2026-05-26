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
 * 测评任务实体
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_session", autoResultMap = true)
public class EvaluationSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long learningSpaceId;

    private String sessionType;

    private String title;

    private Long chapterId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> targetKnowledgePoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> questionIds;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> difficultyDistribution;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityDistribution;

    private Integer totalQuestionCount;

    private Integer answeredCount;

    private BigDecimal totalScore;

    private BigDecimal actualScore;

    private BigDecimal accuracy;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> agentStrategyJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
