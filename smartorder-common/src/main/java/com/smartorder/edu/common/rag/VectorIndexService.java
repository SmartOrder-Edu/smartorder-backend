package com.smartorder.edu.common.rag;


import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.rag.VectorIndexDocument;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.extern.slf4j.Slf4j;
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

    // 以你截图为准的集合名称
    private static final String COLLECTION_NAME = "rag_chunks";
    
    // 嵌入模型批量处理大小
    private static final int EMBEDDING_BATCH_SIZE = 100;

    public VectorIndexService(MilvusClientV2 milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 将 Chunk 向量化并写入 Milvus
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

        // 1. 调用 Embedding 模型，将文本批量转换为向量 (512维)
        log.info("开始生成文本向量，数量: {}", textsToEmbed.size());
        List<TextSegment> textSegments = textsToEmbed.stream()
                .map(TextSegment::from)
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(textSegments).content();

        // 2. 将生成的向量装配到待插入的文档中
        List<JSONObject> milvusData = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            VectorIndexDocument doc = docs.get(i);
            List<Float> vectorList = embeddings.get(i).vectorAsList();

            // 组装 Milvus V2 SDK 需要的 JSONObject 格式
            JSONObject row = new JSONObject();
            // 主键 (对应你的向量集合配置，必须叫 vector_id 或你建表时指定的 PK 名字)
            row.put("vector_id", doc.getId());
            // 向量字段 (对应你集合中 512 维的 float_vector 字段，假设叫 embedding)
            row.put("embedding", vectorList);
            // 文本内容
            row.put("content", doc.getText());

            // 将其它元数据铺平放入（或者放入一个 JSON 字段，取决于你的 Milvus Collection Schema 是否开启了 Dynamic Field）
            Map<String, Object> metadata = doc.getMetadata();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() != null) {
                    row.put(entry.getKey(), entry.getValue());
                }
            }
            milvusData.add(row);
        }

        // 3. 执行 Upsert 操作
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

    private void doUpsert(List<JSONObject> milvusData) {
        try {
            UpsertReq upsertReq = UpsertReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .data(milvusData)
                    .build();

            UpsertResp response = milvusClient.upsert(upsertReq);
            log.info("成功写入(Upsert) Milvus 向量库，集合: {}, 插入数量: {}",
                    COLLECTION_NAME, response.getUpsertCnt());
        } catch (Exception e) {
            log.error("写入 Milvus 向量库失败！", e);
            throw new RuntimeException("Milvus upsert failed", e);
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}