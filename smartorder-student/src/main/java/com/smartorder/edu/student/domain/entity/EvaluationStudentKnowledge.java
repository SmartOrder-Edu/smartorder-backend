package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测评学生知识点评估实体
 */
@Data
@NoArgsConstructor
@TableName("evaluation_student_knowledge")
public class EvaluationStudentKnowledge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long learningSpaceId;

    private Long knowledgePointId;

    private BigDecimal masteryScore;

    private BigDecimal accuracy;

    private BigDecimal averageScore;

    private Integer averageDurationSeconds;

    private Integer correctCount;

    private Integer wrongCount;

    private Integer weaknessLevel;

    private Long lastSessionId;

    private LocalDateTime lastEvaluatedAt;

    private LocalDateTime updatedAt;
}
