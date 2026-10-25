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
 * @deprecated 遗留实体：全仓没有对应的 Mapper，表 {@code evaluation_student_knowledge}
 * 只出现在初始化脚本 {@code sql/test-question/agent_evaluation.sql} 中，运行期无任何读写。
 * 现行知识点掌握度由 {@code EvaluationAnswerRecord}、{@code EvaluationQuestionKnowledgeNode}
 * 与 {@code StudentProfile} 聚合得出。本类可在确认后删除。
 *
 * @author 马强
 * @since 2026-05-27
 */
@Deprecated(since = "2.0", forRemoval = false)
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
