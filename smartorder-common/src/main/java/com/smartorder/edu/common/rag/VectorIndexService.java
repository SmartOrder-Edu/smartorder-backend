package com.smartorder.edu.common.rag;


import com.google.gson.JsonObject;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.rag.VectorIndexDocument;
import com.smartorder.edu.common.rag.vector.TfidfSparseVectorService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class VectorIndexService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final TfidfSparseVectorService tfidfService;

    @Value("${milvus.collection-name:rag_chunks}")
    private String collectionName;

    // 嵌入模型批量处理大小
    private static final int EMBEDDING_BATCH_SIZE = 100;

    public VectorIndexService(MilvusClientV2 milvusClient,
                              @Qualifier("embeddingModel") EmbeddingModel embeddingModel,
                              TfidfSparseVectorService tfidfService) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.tfidfService = tfidfService;
    }

    /**
     * 将 Chunk 向量化并写入 Milvus（密集向量 + 稀疏向量）
     */
    public void indexChunks(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        log.info("开始处理 {} 个文档块的向量化索引", chunks.size());

        // 分批处理，避免内存溢出和API限制
        for (int batchStart = 0; batchStart < chunks.size(); batchStart += EMBEDDING_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + EMBEDDING_BATCH_SIZE, chunks.size());
            List<KnowledgeChunk> batchChunks = chunks.subList(batchStart, batchEnd);

            log.info("处理批次 {}/{}，数量: {}",
                    (batchStart / EMBEDDING_BATCH_SIZE) + 1,
                    (int) Math.ceil((double) chunks.size() / EMBEDDING_BATCH_SIZE),
                    batchChunks.size());

            processBatch(batchChunks);
        }

        log.info("完成所有文档块的向量化索引，总数: {}", chunks.size());
    }
    
    /**
     * 处理单个批次的文档块
     */
    private void processBatch(List<KnowledgeChunk> batchChunks) {
        List<VectorIndexDocument> docs = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();

        for (KnowledgeChunk chunk : batchChunks) {
            String embeddingText = buildEmbeddingText(chunk);
            textsToEmbed.add(embeddingText);

            docs.add(VectorIndexDocument.builder()
                    .id(chunk.getVectorId())
                    .text(embeddingText)
                    .metadata(buildMetadata(chunk))
                    .build());
        }

        // 1. 调用 Embedding 模型，将文本批量转换为向量 (1024维)
        log.info("开始生成文本向量，数量: {}", textsToEmbed.size());
        List<TextSegment> textSegments = textsToEmbed.stream()
                .map(TextSegment::from)
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(textSegments).content();

        // 2. 生成稀疏向量 (TF-IDF)
        log.info("开始生成稀疏向量...");
        tfidfService.updateVocabulary(textsToEmbed);
        List<Map<Long, Float>> sparseVectors = tfidfService.computeTfidfVectors(textsToEmbed);

        // 3. 将生成的向量装配到待插入的文档中
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<JsonObject> milvusData = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            VectorIndexDocument doc = docs.get(i);
            List<Float> denseVector = embeddings.get(i).vectorAsList();
            Map<Long, Float> sparseVector = sparseVectors.get(i);

            // 组装 Milvus V2 SDK 需要的 JsonObject 格式
            JsonObject row = new JsonObject();
            row.addProperty("vector_id", doc.getId());
            row.add("dense_vector", gson.toJsonTree(denseVector));
            row.add("sparse_vector", gson.toJsonTree(sparseVector));
            row.addProperty("content", doc.getText());

            // 将其它元数据铺平放入
            Map<String, Object> metadata = doc.getMetadata();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() != null) {
                    row.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
                }
            }
            milvusData.add(row);
        }

        // 4. 执行 Upsert 操作
        doUpsert(milvusData);
    }

    private String buildEmbeddingText(KnowledgeChunk chunk) {
        return """
                标题路径：%s
                章节标题：%s
                正文：
                %s
                """.formatted(
                nullToEmpty(chunk.getTitlePath()),
                nullToEmpty(chunk.getSectionTitle()),
                nullToEmpty(chunk.getContent())
        );
    }

    private Map<String, Object> buildMetadata(KnowledgeChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunk_id", chunk.getId());
        metadata.put("user_id", chunk.getUserId());
        metadata.put("knowledge_base_id", chunk.getKnowledgeBaseId());
        metadata.put("file_id", chunk.getFileId());
        metadata.put("document_id", chunk.getDocumentId());
        metadata.put("chunk_index", chunk.getChunkIndex());
        metadata.put("chunk_type", chunk.getChunkType());
        metadata.put("section_title", chunk.getSectionTitle());
        metadata.put("title_path", chunk.getTitlePath());
        metadata.put("page_number", chunk.getPageNumber());
        metadata.put("embedding_model", chunk.getEmbeddingModel());
        return metadata;
    }

    private void doUpsert(List<JsonObject> milvusData) {
        try {
            UpsertReq upsertReq = UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(milvusData)
                    .build();

            UpsertResp response = milvusClient.upsert(upsertReq);
            log.info("成功写入(Upsert) Milvus 向量库，集合: {}, 插入数量: {}",
                    collectionName, response.getUpsertCnt());
        } catch (Exception e) {
            log.error("写入 Milvus 向量库失败！", e);
            throw new RuntimeException("Milvus upsert failed", e);
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}