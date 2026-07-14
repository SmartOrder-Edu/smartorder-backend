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

@Data
@NoArgsConstructor
@TableName(value = "learning_evidence_event", autoResultMap = true)
public class LearningEvidenceEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long spaceId;

    private String sourceType;

    private Long sourceId;

    private Long questionId;

    private Long knowledgeNodeId;

    private BigDecimal score;

    private Integer isCorrect;

    private Integer difficulty;

    private Integer timeSpentSeconds;

    private Integer expectedSeconds;

    private Integer hintCount;

    private BigDecimal evidenceWeight;

    private String errorType;

    private BigDecimal semanticConfidence;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private Integer isDeleted;

    private LocalDateTime createTime;
}
