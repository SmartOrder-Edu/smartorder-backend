package com.smartorder.edu.common.rag.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 混合搜索结果模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridSearchResult {

    /**
     * 向量 ID
     */
    private String vectorId;

    /**
     * 文本内容
     */
    private String content;

    /**
     * 密集向量相似度分数
     */
    private float denseScore;

    /**
     * 稀疏向量相似度分数
     */
    private float sparseScore;

    /**
     * 融合后的综合分数
     */
    private float hybridScore;

    /**
     * 密集向量排名
     */
    private int denseRank;

    /**
     * 稀疏向量排名
     */
    private int sparseRank;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * chunk ID
     */
    private Long chunkId;

    /**
     * 知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 文件 ID
     */
    private Long fileId;

    /**
     * 文档 ID
     */
    private Long documentId;

    /**
     * 章节标题
     */
    private String sectionTitle;

    /**
     * 标题路径
     */
    private String titlePath;

    /**
     * 页码
     */
    private Integer pageNumber;

    /**
     * 计算 RRF (Reciprocal Rank Fusion) 分数
     *
     * @param k  RRF 常数（通常为 60）
     * @param denseWeight  密集向量权重
     * @param sparseWeight 稀疏向量权重
     */
    public void calculateRRFScore(int k, float denseWeight, float sparseWeight) {
        double denseRRF = denseWeight / (k + denseRank);
        double sparseRRF = sparseWeight / (k + sparseRank);
        this.hybridScore = (float) (denseRRF + sparseRRF);
    }

    /**
     * 计算加权线性组合分数
     *
     * @param denseWeight  密集向量权重
     * @param sparseWeight 稀疏向量权重
     */
    public void calculateWeightedScore(float denseWeight, float sparseWeight) {
        this.hybridScore = denseScore * denseWeight + sparseScore * sparseWeight;
    }
}
