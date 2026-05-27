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
 *
 * @author 马强
 * @since 2026-05-27
 */
@Data
@NoArgsConstructor
@TableName("evaluation_student_knowledge")
public class EvaluationStudentKnowledge {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 知识点ID */
    private Long knowledgePointId;

    /** 掌握度，0-1 */
    private BigDecimal masteryScore;

    /** 正确率，0-1 */
    private BigDecimal accuracy;

    /** 平均得分 */
    private BigDecimal averageScore;

    /** 平均作答耗时 */
    private Integer averageDurationSeconds;

    /** 答对次数 */
    private Integer correctCount;

    /** 答错次数 */
    private Integer wrongCount;

    /** 薄弱等级：0正常，1轻微，2中等，3严重 */
    private Integer weaknessLevel;

    /** 最近一次测评任务ID */
    private Long lastSessionId;

    /** 最近评估时间 */
    private LocalDateTime lastEvaluatedAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
