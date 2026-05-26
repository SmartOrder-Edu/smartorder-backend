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
 * 测评答题记录实体
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_answer_record", autoResultMap = true)
public class EvaluationAnswerRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long userId;

    private Long learningSpaceId;

    private Long questionId;

    private Long knowledgePointId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> userAnswer;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> standardAnswer;

    private Integer isCorrect;

    private BigDecimal score;

    private Integer answerDurationSeconds;

    private Integer usedHint;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> judgeResult;

    private String errorType;

    private String errorReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
