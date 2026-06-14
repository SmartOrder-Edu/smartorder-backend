package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 查询请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryRequest {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 查询文本
     */
    private String query;

    /**
     * 文件ID集合（用户指定要检索的文件）
     */
    private List<Long> fileIds;

    /**
     * 知识库ID集合（用户指定要检索的知识库）
     */
    private List<Long> knowledgeBaseIds;

    /**
     * 返回结果数量，默认10
     */
    @Builder.Default
    private Integer topK = 10;

    /**
     * 检索模式：auto/simple/medium/complex
     * auto: 由系统自动分析决定
     * simple: 向量+关键词混合检索
     * medium: 组合检索（向量+图）
     * complex: 图RAG检索（多跳推理）
     */
    @Builder.Default
    private String retrievalMode = "auto";
}
