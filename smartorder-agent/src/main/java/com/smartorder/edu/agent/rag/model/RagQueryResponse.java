package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 查询响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 检索到的文档块列表
     */
    private List<RagChunk> chunks;

    /**
     * 组装好的上下文文本（可直接注入到 LLM 提示词中）
     */
    private String contextText;

    /**
     * 使用的检索模式
     */
    private String usedRetrievalMode;

    /**
     * 查询分析结果
     */
    private QueryAnalysisResult analysisResult;

    /**
     * RAG 评估结果
     */
    private RagEvaluationResult evaluationResult;

    /**
     * 是否降级过
     */
    private boolean degraded;

    /**
     * 降级原因
     */
    private String degradeReason;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 构建成功响应
     */
    public static RagQueryResponse success(List<RagChunk> chunks, String contextText,
                                            String usedRetrievalMode,
                                            QueryAnalysisResult analysisResult,
                                            RagEvaluationResult evaluationResult,
                                            boolean degraded, String degradeReason) {
        return RagQueryResponse.builder()
                .success(true)
                .chunks(chunks)
                .contextText(contextText)
                .usedRetrievalMode(usedRetrievalMode)
                .analysisResult(analysisResult)
                .evaluationResult(evaluationResult)
                .degraded(degraded)
                .degradeReason(degradeReason)
                .build();
    }

    /**
     * 构建失败响应
     */
    public static RagQueryResponse failure(String errorMessage) {
        return RagQueryResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
