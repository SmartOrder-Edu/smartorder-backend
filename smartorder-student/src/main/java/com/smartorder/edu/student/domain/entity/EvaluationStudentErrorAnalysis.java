package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测评学生错因分析实体
 */
@Data
@NoArgsConstructor
@TableName("evaluation_student_error_analysis")
public class EvaluationStudentErrorAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long learningSpaceId;

    private Long sessionId;

    private Long questionId;

    private Long knowledgePointId;

    private String errorType;

    private String errorReason;

    private String evidence;

    private String suggestion;

    private BigDecimal confidence;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
