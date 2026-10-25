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
 *
 * @deprecated 遗留实体：全仓没有对应的 Mapper，表 {@code evaluation_student_error_analysis}
 * 只出现在初始化脚本 {@code sql/test-question/agent_evaluation.sql} 中，运行期无任何读写。
 * 现行错因与学情结论由 {@code EvaluationLearningReport}（测评报告）承载。本类可在确认后删除。
 *
 * @author 马强
 * @since 2026-05-27
 */
@Deprecated(since = "2.0", forRemoval = false)
@Data
@NoArgsConstructor
@TableName("evaluation_student_error_analysis")
public class EvaluationStudentErrorAnalysis {

    /** 错因分析ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 测评任务ID */
    private Long sessionId;

    /** 题目ID */
    private Long questionId;

    /** 知识点ID */
    private Long knowledgePointId;

    /** 错误类型：concept_missing/calculation_error/confused_concept/incomplete_answer/logic_error/code_bug */
    private String errorType;

    /** 错误原因 */
    private String errorReason;

    /** 判断依据 */
    private String evidence;

    /** 改进建议 */
    private String suggestion;

    /** 分析置信度，0-1 */
    private BigDecimal confidence;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
