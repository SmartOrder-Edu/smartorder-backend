package com.smartorder.edu.agent.rag.evaluation;

import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RagEvaluationResult;

import java.util.List;

/**
 * RAG 评估器接口
 * 评估检索结果的质量
 */
public interface RagEvaluator {

    /**
     * 评估检索结果
     *
     * @param query   用户查询
     * @param chunks  检索到的文档块
     * @return 评估结果
     */
    RagEvaluationResult evaluate(String query, List<RagChunk> chunks);
}
