package com.smartorder.edu.common.rag.vector;

import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合搜索服务
 * 结合 EmbeddingModel 密集向量和 TF-IDF 稀疏向量进行混合搜索
 */
@Slf4j
@Service
public class HybridSearchService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;
    private final TfidfSparseVectorService tfidfService;

    @Value("${hybrid-search.collection-name:hybrid_chunks}")
    private String collectionName;

    @Value("${hybrid-search.dense-weight:0.7}")
    private float denseWeight;

    @Value("${hybrid-search.sparse-weight:0.3}")
    private float sparseWeight;

    @Value("${hybrid-search.default-top-k:10}")
    private int defaultTopK;

    @Value("${hybrid-search.rrf-k:60}")
    private int rrfK;

    public HybridSearchService(MilvusClientV2 milvusClient,
                               EmbeddingModel embeddingModel,
                               TfidfSparseVectorService tfidfService) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.tfidfService = tfidfService;
    }

    /**
     * 执行混合搜索
     */
    public List<HybridSearchResult> hybridSearch(String query, Long knowledgeBaseId, int topK) {
        log.info("开始混合搜索，查询: {}, 知识库: {}, topK: {}", query, knowledgeBaseId, topK);

        // 1. 生成密集向量 (使用现有 EmbeddingModel)
        List<Float> denseVector = generateDenseVector(query);
        log.debug("密集向量生成完成，维度: {}", denseVector.size());

        // 2. 生成稀疏向量 (TF-IDF)
        Map<Long, Float> sparseVector = tfidfService.computeTfidfVector(query);
        log.debug("稀疏向量生成完成，非零元素: {}", sparseVector.size());

        // 3. 执行密集向量搜索
        List<HybridSearchResult> denseResults = searchDense(denseVector, knowledgeBaseId, topK * 2);
        log.debug("密集向量搜索完成，结果数: {}", denseResults.size());

        // 4. 执行稀疏向量搜索
        List<HybridSearchResult> sparseResults = searchSparse(sparseVector, knowledgeBaseId, topK * 2);
        log.debug("稀疏向量搜索完成，结果数: {}", sparseResults.size());

        // 5. 融合结果
        List<HybridSearchResult> mergedResults = mergeResults(denseResults, sparseResults);

        // 6. 按混合分数排序并截取 topK
        List<HybridSearchResult> finalResults = mergedResults.stream()
                .sorted(Comparator.comparing(HybridSearchResult::getHybridScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        log.info("混合搜索完成，返回结果数: {}", finalResults.size());
        return finalResults;
    }

    /**
     * 生成密集向量 (使用现有 EmbeddingModel)
     */
    public List<Float> generateDenseVector(String text) {
        TextSegment segment = TextSegment.from(text);
        Embedding embedding = embeddingModel.embed(segment).content();
        return embedding.vectorAsList();
    }

    /**
     * 批量生成密集向量
     */
    public List<List<Float>> generateDenseVectors(List<String> texts) {
        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .collect(Collectors.toList());
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        return embeddings.stream()
                .map(Embedding::vectorAsList)
                .collect(Collectors.toList());
    }

    /**
     * 密集向量搜索
     */
    private List<HybridSearchResult> searchDense(List<Float> denseVector, Long knowledgeBaseId, int topK) {
        try {
            List<BaseVector> data = Collections.singletonList(new FloatVec(denseVector));
            SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .annsField("dense_vector")
                    .topK(topK);

            if (knowledgeBaseId != null) {
                builder.filter("knowledge_base_id == " + knowledgeBaseId);
            }

            SearchResp resp = milvusClient.search(builder.build());
            return parseSearchResults(resp, true);

        } catch (Exception e) {
            log.error("密集向量搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 稀疏向量搜索
     */
    private List<HybridSearchResult> searchSparse(Map<Long, Float> sparseVector, Long knowledgeBaseId, int topK) {
        try {
            SortedMap<Long, Float> sortedSparseVector = new TreeMap<>(sparseVector);
            List<BaseVector> data = Collections.singletonList(new SparseFloatVec(sortedSparseVector));
            SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .annsField("sparse_vector")
                    .topK(topK);

            if (knowledgeBaseId != null) {
                builder.filter("knowledge_base_id == " + knowledgeBaseId);
            }

            SearchResp resp = milvusClient.search(builder.build());
            return parseSearchResults(resp, false);

        } catch (Exception e) {
            log.error("稀疏向量搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析搜索结果
     */
    private List<HybridSearchResult> parseSearchResults(SearchResp resp, boolean isDense) {
        List<HybridSearchResult> results = new ArrayList<>();

        if (resp == null || resp.getSearchResults() == null) {
            return results;
        }

        int rank = 1;
        for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
            if (resultList == null) {
                continue;
            }
            for (SearchResp.SearchResult result : resultList) {
                Map<String, Object> entity = result.getEntity();
                if (entity == null) {
                    entity = new HashMap<>();
                }

                HybridSearchResult searchResult = HybridSearchResult.builder()
                        .vectorId(getStringField(entity, "vector_id"))
                        .content(getStringField(entity, "content"))
                        .chunkId(getLongField(entity, "chunk_id"))
                        .knowledgeBaseId(getLongField(entity, "knowledge_base_id"))
                        .fileId(getLongField(entity, "file_id"))
                        .documentId(getLongField(entity, "document_id"))
                        .sectionTitle(getStringField(entity, "section_title"))
                        .titlePath(getStringField(entity, "title_path"))
                        .pageNumber(getIntField(entity, "page_number"))
                        .metadata(entity)
                        .build();

                Float score = result.getScore();
                float scoreValue = score != null ? score : 0f;

                if (isDense) {
                    searchResult.setDenseScore(scoreValue);
                    searchResult.setDenseRank(rank);
                } else {
                    searchResult.setSparseScore(scoreValue);
                    searchResult.setSparseRank(rank);
                }

                results.add(searchResult);
                rank++;
            }
        }

        return results;
    }

    /**
     * 融合密集和稀疏搜索结果 (RRF 算法)
     */
    private List<HybridSearchResult> mergeResults(List<HybridSearchResult> denseResults,
                                                   List<HybridSearchResult> sparseResults) {
        Map<String, HybridSearchResult> mergedMap = new LinkedHashMap<>();

        // 处理密集向量结果
        for (HybridSearchResult result : denseResults) {
            String key = result.getVectorId();
            if (key == null) continue;

            HybridSearchResult existing = mergedMap.get(key);
            if (existing == null) {
                existing = HybridSearchResult.builder()
                        .vectorId(key)
                        .content(result.getContent())
                        .chunkId(result.getChunkId())
                        .knowledgeBaseId(result.getKnowledgeBaseId())
                        .fileId(result.getFileId())
                        .documentId(result.getDocumentId())
                        .sectionTitle(result.getSectionTitle())
                        .titlePath(result.getTitlePath())
                        .pageNumber(result.getPageNumber())
                        .metadata(result.getMetadata())
                        .build();
                mergedMap.put(key, existing);
            }

            existing.setDenseScore(result.getDenseScore());
            existing.setDenseRank(result.getDenseRank());
        }

        // 处理稀疏向量结果
        for (HybridSearchResult result : sparseResults) {
            String key = result.getVectorId();
            if (key == null) continue;

            HybridSearchResult existing = mergedMap.get(key);
            if (existing == null) {
                existing = HybridSearchResult.builder()
                        .vectorId(key)
                        .content(result.getContent())
                        .chunkId(result.getChunkId())
                        .knowledgeBaseId(result.getKnowledgeBaseId())
                        .fileId(result.getFileId())
                        .documentId(result.getDocumentId())
                        .sectionTitle(result.getSectionTitle())
                        .titlePath(result.getTitlePath())
                        .pageNumber(result.getPageNumber())
                        .metadata(result.getMetadata())
                        .build();
                mergedMap.put(key, existing);
            }

            existing.setSparseScore(result.getSparseScore());
            existing.setSparseRank(result.getSparseRank());
        }

        // 计算混合分数
        for (HybridSearchResult result : mergedMap.values()) {
            if (result.getDenseRank() == 0) {
                result.setDenseRank(denseResults.size() + 100);
            }
            if (result.getSparseRank() == 0) {
                result.setSparseRank(sparseResults.size() + 100);
            }
            result.calculateRRFScore(rrfK, denseWeight, sparseWeight);
        }

        return new ArrayList<>(mergedMap.values());
    }

    /**
     * 索引单个文档
     */
    public void indexDocument(String vectorId, String content, Map<String, Object> metadata) {
        log.info("开始索引文档，vectorId: {}", vectorId);

        // 1. 生成密集向量
        List<Float> denseVector = generateDenseVector(content);

        // 2. 生成稀疏向量
        Map<Long, Float> sparseVector = tfidfService.computeTfidfVector(content);

        // 3. 构建 Milvus 数据
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject row = new JsonObject();
        row.addProperty("vector_id", vectorId);
        row.add("dense_vector", gson.toJsonTree(denseVector));
        row.add("sparse_vector", gson.toJsonTree(sparseVector));
        row.addProperty("content", content);

        if (metadata != null) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() != null) {
                    row.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
                }
            }
        }

        // 4. 写入 Milvus
        try {
            UpsertReq upsertReq = UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(row))
                    .build();

            milvusClient.upsert(upsertReq);
            log.info("文档索引成功，vectorId: {}", vectorId);
        } catch (Exception e) {
            log.error("文档索引失败，vectorId: {}", vectorId, e);
            throw new RuntimeException("Failed to index document", e);
        }
    }

    /**
     * 批量索引文档
     */
    public void batchIndexDocuments(List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        log.info("开始批量索引文档，数量: {}", documents.size());

        // 1. 批量生成密集向量
        List<String> contents = documents.stream()
                .map(doc -> (String) doc.get("content"))
                .collect(Collectors.toList());
        List<List<Float>> denseVectors = generateDenseVectors(contents);

        // 2. 批量生成稀疏向量
        List<Map<Long, Float>> sparseVectors = tfidfService.computeTfidfVectors(contents);

        // 3. 更新词汇表
        tfidfService.updateVocabulary(contents);

        // 4. 构建 Milvus 数据
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<JsonObject> milvusData = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> doc = documents.get(i);

            JsonObject row = new JsonObject();
            row.addProperty("vector_id", (String) doc.get("vectorId"));
            row.add("dense_vector", gson.toJsonTree(denseVectors.get(i)));
            row.add("sparse_vector", gson.toJsonTree(sparseVectors.get(i)));
            row.addProperty("content", contents.get(i));

            Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
            if (metadata != null) {
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    if (entry.getValue() != null) {
                        row.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
                    }
                }
            }

            milvusData.add(row);
        }

        // 5. 批量写入 Milvus
        try {
            UpsertReq upsertReq = UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(milvusData)
                    .build();

            milvusClient.upsert(upsertReq);
            log.info("批量索引成功，数量: {}", documents.size());
        } catch (Exception e) {
            log.error("批量索引失败", e);
            throw new RuntimeException("Failed to batch index documents", e);
        }
    }

    // 辅助方法
    private String getStringField(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        return value != null ? value.toString() : null;
    }

    private Long getLongField(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private Integer getIntField(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
}
