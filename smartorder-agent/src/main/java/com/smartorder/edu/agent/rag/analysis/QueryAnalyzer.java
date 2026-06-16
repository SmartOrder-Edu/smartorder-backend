package com.smartorder.edu.agent.rag.analysis;

import com.smartorder.edu.agent.rag.model.QueryAnalysisResult;

/**
 * 查询分析器接口
 * 分析用户查询的复杂度、关系、推理深度和实体
 */
public interface QueryAnalyzer {

    /**
     * 分析查询
     *
     * @param query 用户查询文本
     * @return 查询分析结果
     */
    QueryAnalysisResult analyze(String query);
}
