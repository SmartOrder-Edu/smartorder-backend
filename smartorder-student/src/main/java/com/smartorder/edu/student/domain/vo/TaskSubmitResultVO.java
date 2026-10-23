package com.smartorder.edu.student.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 任务提交判分结果 VO
 *
 * <p>任务提交后返回的即时判分结果，包含客观题得分和主观题待判分状态</p>
 *
 * @since 2026-07-07
 */
@Data
@Builder
public class TaskSubmitResultVO {

    /** 任务ID */
    private Long taskId;

    /** 总分 */
    private BigDecimal totalScore;

    /** 实际得分（含已完成判分的题目） */
    private BigDecimal actualScore;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 客观题得分 */
    private BigDecimal objectiveScore;

    /** 客观题满分 */
    private BigDecimal objectiveTotal;

    /** 主观题待判分数量 */
    private int subjectivePending;

    /** 每题详情 */
    private List<AnswerDetailVO> detail;

    @Data
    @Builder
    public static class AnswerDetailVO {
        private Long questionId;
        private Boolean isCorrect;
        private BigDecimal score;
        private String status; // graded / pending_review
        private Map<String, Object> userAnswer;
        private Map<String, Object> judgeResult;
    }
}
