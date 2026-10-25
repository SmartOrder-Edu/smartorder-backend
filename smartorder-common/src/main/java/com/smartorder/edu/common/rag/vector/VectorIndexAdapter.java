package com.smartorder.edu.common.rag.vector;

import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 向量索引适配器
 * 桥接现有的知识块处理流程和混合搜索服务
 *
 * @deprecated 本适配器已无调用方（全仓无任何注入点），其下游 {@link HybridSearchService}
 * 也已停用。现行向量写入与检索统一走 {@link com.smartorder.edu.common.rag.VectorIndexService}
 * （集合 {@code rag_chunks}），由它自行完成稠密 + 稀疏向量与 RRF 融合。新代码不要再用本类，
 * 可在确认后连同 {@link HybridSearchService} 一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexAdapter {

    private final HybridSearchService hybridSearchService;
    private final TfidfSparseVectorService tfidfService;

    /**
     * 将 KnowledgeChunk 列表索引到混合搜索 Collection
     */
    public void indexChunksToHybrid(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        log.info("开始将 {} 个文档块索引到混合搜索 Collection", chunks.size());

        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (KnowledgeChunk chunk : chunks) {
            String content = buildContent(chunk);
            contents.add(content);

            Map<String, Object> doc = new HashMap<>();
            doc.put("vectorId", chunk.getVectorId());
            doc.put("content", content);
            doc.put("metadata", buildMetadata(chunk));
            documents.add(doc);
        }

        // 更新 TF-IDF 词汇表
        tfidfService.updateVocabulary(contents);

        // 批量索引
        hybridSearchService.batchIndexDocuments(documents);

        log.info("成功将 {} 个文档块索引到混合搜索 Collection", chunks.size());
    }

    /**
     * 索引单个知识块
     */
    public void indexSingleChunk(KnowledgeChunk chunk) {
        String content = buildContent(chunk);
        hybridSearchService.indexDocument(chunk.getVectorId(), content, buildMetadata(chunk));
    }

    private String buildContent(KnowledgeChunk chunk) {
        StringBuilder sb = new StringBuilder();

        if (chunk.getTitlePath() != null && !chunk.getTitlePath().isEmpty()) {
            sb.append("标题路径：").append(chunk.getTitlePath()).append("\n");
        }

        if (chunk.getSectionTitle() != null && !chunk.getSectionTitle().isEmpty()) {
            sb.append("章节标题：").append(chunk.getSectionTitle()).append("\n");
        }

        if (chunk.getContent() != null) {
            sb.append("正文：\n").append(chunk.getContent());
        }

        return sb.toString();
    }

    private Map<String, Object> buildMetadata(KnowledgeChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put("chunk_id", chunk.getId());
        metadata.put("user_id", chunk.getUserId());
        metadata.put("knowledge_base_id", chunk.getKnowledgeBaseId());
        metadata.put("file_id", chunk.getFileId());
        metadata.put("document_id", chunk.getDocumentId());
        metadata.put("chunk_index", chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0);
        metadata.put("chunk_type", chunk.getChunkType() != null ? chunk.getChunkType() : "text");
        metadata.put("section_title", chunk.getSectionTitle());
        metadata.put("title_path", chunk.getTitlePath());
        metadata.put("page_number", chunk.getPageNumber() != null ? chunk.getPageNumber() : 1);
        metadata.put("embedding_model", "bge-m3+tfidf");

        return metadata;
    }
}
