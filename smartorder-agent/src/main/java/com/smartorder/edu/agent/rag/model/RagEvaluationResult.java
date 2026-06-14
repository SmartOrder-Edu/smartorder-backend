package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 评估结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvaluationResult {

    /**
     * 上下文相关性评分 0-1
     * 检索出来的上下文内容是否与用户查询高度相关
     */
    private double contextRelevance;

    /**
     * 可信度评分 0-1
     * 生成的答案是否基于上下文
     */
    private double faithfulness;

    /**
     * 答案相关性评分 0-1
     * 生成的答案是否能正确回答用户的查询
     */
    private double answerRelevance;

    /**
     * 综合评分 0-1
     */
    private double overallScore;

    /**
     * 是否通过评估
     */
    private boolean passed;

    /**
     * 评估原因
     */
    private String reason;

    /**
     * 评估阈值
     */
    private static final double PASS_THRESHOLD = 0.6;

    /**
     * 计算综合评分并判断是否通过
     */
    public void calculateOverall() {
        this.overallScore = (contextRelevance + faithfulness + answerRelevance) / 3.0;
        this.passed = overallScore >= PASS_THRESHOLD;
    }
}
