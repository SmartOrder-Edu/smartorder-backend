package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 检索到的文档块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChunk {

    /**
     * 向量ID
     */
    private String vectorId;

    /**
     * 内容
     */
    private String content;

    /**
     * 分块ID
     */
    private Long chunkId;

    /**
     * 知识库ID
     */
    private Long knowledgeBaseId;

    /**
     * 文件ID
     */
    private Long fileId;

    /**
     * 文档ID
     */
    private Long documentId;

    /**
     * 页码
     */
    private Integer pageNumber;

    /**
     * 章节标题
     */
    private String sectionTitle;

    /**
     * 标题路径
     */
    private String titlePath;

    /**
     * 检索分数
     */
    private double score;

    /**
     * 检索来源：vector/graph/hybrid
     */
    private String source;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 从 AttachmentRagSearchResult 转换
     */
    public static RagChunk fromAttachmentResult(
            com.smartorder.edu.common.model.rag.AttachmentRagSearchResult result) {
        return RagChunk.builder()
                .vectorId(result.getVectorId())
                .content(result.getContent())
                .chunkId(result.getChunkId())
                .knowledgeBaseId(result.getKnowledgeBaseId())
                .fileId(result.getFileId())
                .documentId(result.getDocumentId())
                .pageNumber(result.getPageNumber())
                .sectionTitle(result.getSectionTitle())
                .titlePath(result.getTitlePath())
                .score(result.getScore() != null ? result.getScore() : 0.0)
                .source("vector")
                .metadata(result.getMetadata())
                .build();
    }

    /**
     * 从 HybridSearchResult 转换
     */
    public static RagChunk fromHybridResult(
            com.smartorder.edu.common.rag.vector.HybridSearchResult result) {
        return RagChunk.builder()
                .vectorId(result.getVectorId())
                .content(result.getContent())
                .chunkId(result.getChunkId())
                .knowledgeBaseId(result.getKnowledgeBaseId())
                .fileId(result.getFileId())
                .documentId(result.getDocumentId())
                .pageNumber(result.getPageNumber())
                .sectionTitle(result.getSectionTitle())
                .titlePath(result.getTitlePath())
                .score(result.getHybridScore())
                .source("hybrid")
                .metadata(result.getMetadata())
                .build();
    }

    /**
     * 创建图检索结果
     */
    public static RagChunk fromGraphNode(String nodeId, String nodeName, String description,
                                          String category, Long knowledgeBaseId) {
        return RagChunk.builder()
                .vectorId(nodeId)
                .content(description)
                .knowledgeBaseId(knowledgeBaseId)
                .score(1.0)
                .source("graph")
                .sectionTitle(nodeName)
                .build();
    }
}
