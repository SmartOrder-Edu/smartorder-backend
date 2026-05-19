package com.smartorder.edu.common.rag.vector;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Milvus Collection 初始化服务
 * 负责创建和初始化混合搜索所需的 Collection
 */
@Slf4j
@Service
public class CollectionInitService {

    private final MilvusClientV2 milvusClient;

    @Value("${milvus.collection-name:rag_chunks}")
    private String collectionName;

    @Value("${milvus.dimension:1024}")
    private int denseVectorDim;

    public CollectionInitService(MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 应用启动后自动初始化 Collection
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            initHybridCollection();
        } catch (Exception e) {
            log.error("初始化混合搜索 Collection 失败", e);
        }
    }

    /**
     * 初始化混合搜索 Collection
     */
    public void initHybridCollection() {
        log.info("开始初始化混合搜索 Collection: {}", collectionName);

        if (isCollectionExists()) {
            log.info("Collection {} 已存在，跳过创建", collectionName);
            return;
        }

        CreateCollectionReq.CollectionSchema schema = buildSchema();
        List<IndexParam> indexParams = buildIndexParams();

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build();

        try {
            milvusClient.createCollection(createReq);
            log.info("Collection {} 创建成功", collectionName);
        } catch (Exception e) {
            log.error("创建 Collection {} 失败", collectionName, e);
            throw new RuntimeException("Failed to create collection", e);
        }
    }

    private boolean isCollectionExists() {
        try {
            HasCollectionReq hasReq = HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build();
            return milvusClient.hasCollection(hasReq);
        } catch (Exception e) {
            log.warn("检查 Collection 存在性失败", e);
            return false;
        }
    }

    private CreateCollectionReq.CollectionSchema buildSchema() {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .build();

        // 主键字段
        schema.addField(AddFieldReq.builder()
                .fieldName("vector_id")
                .dataType(DataType.VarChar)
                .maxLength(128)
                .isPrimaryKey(true)
                .build());

        // 密集向量字段 (BGE 512 维)
        schema.addField(AddFieldReq.builder()
                .fieldName("dense_vector")
                .dataType(DataType.FloatVector)
                .dimension(denseVectorDim)
                .build());

        // 稀疏向量字段 (TF-IDF)
        schema.addField(AddFieldReq.builder()
                .fieldName("sparse_vector")
                .dataType(DataType.SparseFloatVector)
                .build());

        // 文本内容
        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .build());

        // 元数据字段
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("user_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("knowledge_base_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("file_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("document_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_index")
                .dataType(DataType.Int32)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_type")
                .dataType(DataType.VarChar)
                .maxLength(50)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("section_title")
                .dataType(DataType.VarChar)
                .maxLength(500)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("title_path")
                .dataType(DataType.VarChar)
                .maxLength(1000)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("page_number")
                .dataType(DataType.Int32)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("embedding_model")
                .dataType(DataType.VarChar)
                .maxLength(100)
                .build());

        return schema;
    }

    private List<IndexParam> buildIndexParams() {
        List<IndexParam> indexParams = new ArrayList<>();

        // 密集向量索引：IVF_FLAT
        Map<String, Object> denseExtraParams = new HashMap<>();
        denseExtraParams.put("nlist", 1024);

        IndexParam denseIndex = IndexParam.builder()
                .fieldName("dense_vector")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(denseExtraParams)
                .build();
        indexParams.add(denseIndex);

        // 稀疏向量索引：SPARSE_INVERTED_INDEX
        Map<String, Object> sparseExtraParams = new HashMap<>();
        sparseExtraParams.put("inverted_index_algo", "DAAT_MAXSCORE");

        IndexParam sparseIndex = IndexParam.builder()
                .fieldName("sparse_vector")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.IP)
                .extraParams(sparseExtraParams)
                .build();
        indexParams.add(sparseIndex);

        return indexParams;
    }

    public boolean isCollectionReady() {
        try {
            return milvusClient.hasCollection(
                    HasCollectionReq.builder()
                            .collectionName(collectionName)
                            .build()
            );
        } catch (Exception e) {
            return false;
        }
    }
}
