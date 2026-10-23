package com.smartorder.edu.common.rag;


import com.google.gson.JsonObject;
import com.smartorder.edu.common.config.MilvusClientProvider;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.rag.AttachmentRagSearchResult;
import com.smartorder.edu.common.model.rag.VectorIndexDocument;
import com.smartorder.edu.common.rag.vector.TfidfSparseVectorService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.response.UpsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorIndexService {

    private final MilvusClientProvider milvusClientProvider;
    private final EmbeddingModel embeddingModel;
    private final TfidfSparseVectorService tfidfService;

    @Value("${milvus.collection-name:rag_chunks}")
    private String collectionName;

    @Value("${hybrid-search.rrf-k:60}")
    private int rrfK;

    @Value("${hybrid-search.dense-weight:0.7}")
    private float denseWeight;

    @Value("${hybrid-search.sparse-weight:0.3}")
    private float sparseWeight;

    // 嵌入模型批量处理大小
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private static final List<String> BUSINESS_FIELDS = List.of(
            "vector_id", "content", "chunk_id", "user_id",
            "knowledge_base_id", "file_id", "document_id", "chunk_index",
            "chunk_type", "section_title", "title_path", "page_number", "embedding_model"
    );

    public VectorIndexService(MilvusClientProvider milvusClientProvider,
                              @Qualifier("embeddingModel") EmbeddingModel embeddingModel,
                              TfidfSparseVectorService tfidfService) {
        this.milvusClientProvider = milvusClientProvider;
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

    public List<AttachmentRagSearchResult> searchByFileIds(String query, Long userId, List<Long> fileIds, int topK) {
        if (query == null || query.isBlank() || userId == null || fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            String filter = buildFileIdsFilter(userId, fileIds);

            // 执行密集向量和稀疏向量检索
            List<AttachmentRagSearchResult> denseResults = searchDense(query, filter, topK);
            List<AttachmentRagSearchResult> sparseResults = searchSparse(query, filter, topK);

            log.info("[VectorIndexService] 文件检索完成，密集结果: {}, 稀疏结果: {}",
                    denseResults.size(), sparseResults.size());

            // RRF 融合
            List<AttachmentRagSearchResult> mergedResults = mergeAndRerank(denseResults, sparseResults);

            // 截取 topK
            return mergedResults.stream()
                    .limit(topK)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("混合检索失败: userId={}, fileIds={}", userId, fileIds, e);
            return Collections.emptyList();
        }
    }

    /**
     * 按知识库ID检索向量（混合检索：密集向量 + 稀疏向量）
     */
    public List<AttachmentRagSearchResult> searchByKnowledgeBaseIds(String query, Long userId,
                                                                     List<Long> knowledgeBaseIds, int topK) {
        if (query == null || query.isBlank() || userId == null
                || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            String filter = buildKnowledgeBaseIdsFilter(userId, knowledgeBaseIds);

            // 调试日志
            log.info("[VectorIndexService] 按知识库混合检索，userId={}, knowledgeBaseIds={}, filter={}",
                    userId, knowledgeBaseIds, filter);

            // 执行密集向量和稀疏向量检索
            List<AttachmentRagSearchResult> denseResults = searchDense(query, filter, topK);
            List<AttachmentRagSearchResult> sparseResults = searchSparse(query, filter, topK);

            log.info("[VectorIndexService] 知识库检索完成，密集结果: {}, 稀疏结果: {}",
                    denseResults.size(), sparseResults.size());

            // RRF 融合
            List<AttachmentRagSearchResult> mergedResults = mergeAndRerank(denseResults, sparseResults);

            // 截取 topK
            return mergedResults.stream()
                    .limit(topK)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("按知识库混合检索失败: userId={}, knowledgeBaseIds={}", userId, knowledgeBaseIds, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 密集向量检索
     */
    private List<AttachmentRagSearchResult> searchDense(String query, String filter, int topK) {
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            log.warn("Milvus 不可用，跳过密集向量检索");
            return Collections.emptyList();
        }
        try {
            Embedding embedding = embeddingModel.embed(TextSegment.from(query)).content();
            List<BaseVector> data = Collections.singletonList(new FloatVec(embedding.vectorAsList()));

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .annsField("dense_vector")
                    .topK(Math.max(1, topK))
                    .filter(filter)
                    .outputFields(BUSINESS_FIELDS)
                    .build();

            SearchResp resp = milvusClient.search(searchReq);
            return parseAttachmentSearchResults(resp);
        } catch (Exception e) {
            log.error("密集向量检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 稀疏向量检索
     */
    private List<AttachmentRagSearchResult> searchSparse(String query, String filter, int topK) {
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            log.warn("Milvus 不可用，跳过稀疏向量检索");
            return Collections.emptyList();
        }
        try {
            Map<Long, Float> sparseVector = tfidfService.computeTfidfVector(query);
            if (sparseVector == null || sparseVector.isEmpty()) {
                log.debug("稀疏向量为空，跳过稀疏检索");
                return Collections.emptyList();
            }

            SortedMap<Long, Float> sortedSparseVector = new TreeMap<>(sparseVector);
            List<BaseVector> data = Collections.singletonList(new SparseFloatVec(sortedSparseVector));

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .annsField("sparse_vector")
                    .topK(Math.max(1, topK))
                    .filter(filter)
                    .outputFields(BUSINESS_FIELDS)
                    .build();

            SearchResp resp = milvusClient.search(searchReq);
            return parseAttachmentSearchResults(resp);
        } catch (Exception e) {
            log.error("稀疏向量检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合密集和稀疏检索结果
     */
    private List<AttachmentRagSearchResult> mergeAndRerank(
            List<AttachmentRagSearchResult> denseResults,
            List<AttachmentRagSearchResult> sparseResults) {

        Map<String, AttachmentRagSearchResult> mergedMap = new LinkedHashMap<>();
        Map<String, Integer> denseRankMap = new HashMap<>();
        Map<String, Integer> sparseRankMap = new HashMap<>();

        // 记录密集向量排名
        for (int i = 0; i < denseResults.size(); i++) {
            String key = denseResults.get(i).getVectorId();
            if (key == null) continue;
            denseRankMap.put(key, i + 1);
            mergedMap.put(key, denseResults.get(i));
        }

        // 记录稀疏向量排名
        for (int i = 0; i < sparseResults.size(); i++) {
            String key = sparseResults.get(i).getVectorId();
            if (key == null) continue;
            sparseRankMap.put(key, i + 1);
            mergedMap.putIfAbsent(key, sparseResults.get(i));
        }

        // 计算 RRF 分数
        for (Map.Entry<String, AttachmentRagSearchResult> entry : mergedMap.entrySet()) {
            String key = entry.getKey();
            AttachmentRagSearchResult result = entry.getValue();

            int denseRank = denseRankMap.getOrDefault(key, denseResults.size() + 100);
            int sparseRank = sparseRankMap.getOrDefault(key, sparseResults.size() + 100);

            double rrfScore = denseWeight / (rrfK + denseRank) + sparseWeight / (rrfK + sparseRank);
            result.setScore((float) rrfScore);
        }

        // 按 RRF 分数排序
        return mergedMap.values().stream()
                .sorted(Comparator.comparing(AttachmentRagSearchResult::getScore).reversed())
                .collect(Collectors.toList());
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
        metadata.put("chunk_index", chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0);
        metadata.put("chunk_type", chunk.getChunkType() != null ? chunk.getChunkType() : "text");
        metadata.put("section_title", chunk.getSectionTitle());
        metadata.put("title_path", chunk.getTitlePath());
        metadata.put("page_number", chunk.getPageNumber() != null ? chunk.getPageNumber() : 1);
        metadata.put("embedding_model", chunk.getEmbeddingModel());
        return metadata;
    }

    private void doUpsert(List<JsonObject> milvusData) {
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            log.error("Milvus 不可用，向量写入失败，集合: {}, 待写入数量: {}",
                    collectionName, milvusData.size());
            throw new IllegalStateException("Milvus 不可用，向量索引写入失败");
        }
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

    private String buildFileIdsFilter(Long userId, List<Long> fileIds) {
        String fileExpr = fileIds.stream()
                .distinct()
                .map(id -> "file_id == " + id)
                .collect(Collectors.joining(" || "));
        return "user_id == " + userId + " && (" + fileExpr + ")";
    }

    private String buildKnowledgeBaseIdsFilter(Long userId, List<Long> knowledgeBaseIds) {
        String kbExpr = knowledgeBaseIds.stream()
                .distinct()
                .map(id -> "knowledge_base_id == " + id)
                .collect(Collectors.joining(" || "));
        return "user_id == " + userId + " && (" + kbExpr + ")";
    }

    private List<AttachmentRagSearchResult> parseAttachmentSearchResults(SearchResp resp) {
        List<AttachmentRagSearchResult> results = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null) {
            return results;
        }

        for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
            if (resultList == null) {
                continue;
            }
            for (SearchResp.SearchResult result : resultList) {
                Map<String, Object> entity = result.getEntity();
                if (entity == null) {
                    entity = Collections.emptyMap();
                }
                results.add(AttachmentRagSearchResult.builder()
                        .vectorId(getStringField(entity, "vector_id"))
                        .content(getStringField(entity, "content"))
                        .chunkId(getLongField(entity, "chunk_id"))
                        .knowledgeBaseId(getLongField(entity, "knowledge_base_id"))
                        .fileId(getLongField(entity, "file_id"))
                        .documentId(getLongField(entity, "document_id"))
                        .pageNumber(getIntField(entity, "page_number"))
                        .sectionTitle(getStringField(entity, "section_title"))
                        .titlePath(getStringField(entity, "title_path"))
                        .score(result.getScore())
                        .metadata(entity)
                        .build());
            }
        }
        return results;
    }

    /**
     * 根据知识库ID删除所有向量
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            log.warn("Milvus 不可用，跳过向量删除（检索按 knowledge_base_id 过滤，残留向量不会被召回），knowledgeBaseId={}",
                    knowledgeBaseId);
            return;
        }
        try {
            String filter = "knowledge_base_id == " + knowledgeBaseId;
            DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .build();
            milvusClient.delete(deleteReq);
            log.info("已删除知识库相关的向量，knowledgeBaseId={}", knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除知识库向量失败，knowledgeBaseId={}", knowledgeBaseId, e);
            throw e;
        }
    }

    /**
     * 根据文件ID删除所有向量
     */
    public void deleteByFileId(Long fileId) {
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            log.warn("Milvus 不可用，跳过向量删除（检索按 file_id 过滤，残留向量不会被召回），fileId={}", fileId);
            return;
        }
        try {
            String filter = "file_id == " + fileId;
            DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .build();
            milvusClient.delete(deleteReq);
            log.info("已删除文件相关的向量，fileId={}", fileId);
        } catch (Exception e) {
            log.error("删除文件向量失败，fileId={}", fileId, e);
            throw e;
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String getStringField(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        return value == null ? null : value.toString();
    }

    private Long getLongField(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer getIntField(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
