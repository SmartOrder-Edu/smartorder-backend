package com.smartorder.edu.common.rag.vector;

import com.smartorder.edu.common.config.MilvusClientProvider;
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

    private final MilvusClientProvider milvusClientProvider;

    @Value("${milvus.collection-name:rag_chunks}")
    private String collectionName;

    @Value("${milvus.dimension:1024}")
    private int denseVectorDim;

    public CollectionInitService(MilvusClientProvider milvusClientProvider) {
        this.milvusClientProvider = milvusClientProvider;
    }

    /**
     * 应用启动后自动初始化 Collection。
     * Milvus 不可用时仅记录告警，不影响应用启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            initHybridCollection();
        } catch (Exception e) {
            log.warn("初始化混合搜索 Collection 失败（不影响应用运行，Milvus 恢复后可手动重试）: {}", e.getMessage());
        }
    }

    /**
     * 初始化混合搜索 Collection
     */
    public void initHybridCollection() {
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            log.warn("Milvus 不可用，跳过 Collection 初始化: {}", collectionName);
            return;
        }
        log.info("开始初始化混合搜索 Collection: {}", collectionName);

        if (isCollectionExists(milvusClient)) {
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

    private boolean isCollectionExists(MilvusClientV2 milvusClient) {
        try {
            HasCollectionReq hasReq = HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build();
            return milvusClient.hasCollection(hasReq);
        } catch (Exception e) {
            log.warn("检查 Collection 存在性失败: {}", e.getMessage());
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
        MilvusClientV2 milvusClient = milvusClientProvider.get();
        if (milvusClient == null) {
            return false;
        }
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
