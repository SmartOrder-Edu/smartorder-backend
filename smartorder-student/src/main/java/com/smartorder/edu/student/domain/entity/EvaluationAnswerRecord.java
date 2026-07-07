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
 * 测评Agent答题记录实体
 *
 * @author 马强
 * @since 2026-05-27
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_answer_record", autoResultMap = true)
public class EvaluationAnswerRecord {

    /** 答题记录ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 测评任务ID */
    private Long sessionId;

    /** 用户ID */
    private Long userId;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 题目ID */
    private Long questionId;

    /** 知识点ID */
    private Long knowledgePointId;

    /** 用户答案JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> userAnswer;

    /** 标准答案快照JSON */
    @TableField(value = "correct_answer", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> correctAnswer;

    /** 是否正确：1正确，0错误 */
    private Integer isCorrect;

    /** 得分 */
    private BigDecimal score;

    /** 作答耗时（秒） */
    private Integer duration;

    /** 是否使用提示：0否，1是 */
    private Integer usedHint;

    /** 判题结果JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> judgeResult;

    /** 错误类型 */
    private String errorType;

    /** 错误原因 */
    private String errorReason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
