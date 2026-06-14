package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询分析结果
 * 基于四维分析：复杂度、关系、推理、实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryAnalysisResult {

    /**
     * 复杂度评分 0-1
     * 0: 简单事实查询
     * 1: 复杂多跳推理查询
     */
    private double complexity;

    /**
     * 关系强度评分 0-1
     * 0: 无明显关系需求
     * 1: 需要深度关系推理
     */
    private double relationScore;

    /**
     * 推理深度评分 0-1
     * 0: 直接检索即可
     * 1: 需要多步推理
     */
    private double reasoningScore;

    /**
     * 提取的实体列表
     */
    private List<String> entities;

    /**
     * 建议的检索模式：simple/medium/complex
     */
    private String suggestedMode;

    /**
     * 分析置信度 0-1
     */
    private double confidence;

    /**
     * 分析原因说明
     */
    private String reason;

    /**
     * 判断是否为简单查询
     */
    public boolean isSimple() {
        return complexity < 0.3 && relationScore < 0.3 && reasoningScore < 0.3;
    }

    /**
     * 判断是否为复杂查询
     */
    public boolean isComplex() {
        return complexity >= 0.7 || relationScore >= 0.7 || reasoningScore >= 0.7;
    }
}
