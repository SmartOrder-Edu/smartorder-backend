package com.smartorder.edu.agent.rag.health;

import com.smartorder.edu.agent.rag.model.HealthCheckResult.ComponentStatus;
import com.smartorder.edu.agent.rag.model.HealthCheckResult;
import com.smartorder.edu.common.rag.vector.CollectionInitService;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 系统健康检查器
 * 在系统启动时检查核心依赖的连通性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagHealthChecker {

    private final CollectionInitService collectionInitService;
    private final INeo4jKnowledgeGraphService neo4jService;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    private HealthCheckResult lastCheckResult;
    private volatile boolean initialized = false;

    /**
     * 系统启动后执行健康检查
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[RagHealthChecker] 开始 RAG 系统健康检查...");
        lastCheckResult = check();
        initialized = true;

        if (lastCheckResult.isReady()) {
            log.info("[RagHealthChecker] RAG 系统健康检查通过，所有组件就绪");
        } else {
            log.error("[RagHealthChecker] RAG 系统健康检查失败: {}", lastCheckResult.getErrorMessage());
        }
    }

    /**
     * 执行健康检查
     */
    public HealthCheckResult check() {
        Map<String, ComponentStatus> statuses = new LinkedHashMap<>();
        boolean allHealthy = true;
        StringBuilder errorMsg = new StringBuilder();

        // 1. 检查 Milvus
        ComponentStatus milvusStatus = checkMilvus();
        statuses.put("milvus", milvusStatus);
        if (!milvusStatus.isHealthy()) {
            allHealthy = false;
            errorMsg.append("Milvus: ").append(milvusStatus.getMessage()).append("; ");
        }

        // 2. 检查 Neo4j
        ComponentStatus neo4jStatus = checkNeo4j();
        statuses.put("neo4j", neo4jStatus);
        if (!neo4jStatus.isHealthy()) {
            allHealthy = false;
            errorMsg.append("Neo4j: ").append(neo4jStatus.getMessage()).append("; ");
        }

        // 3. 检查 LLM
        ComponentStatus llmStatus = checkLlm();
        statuses.put("llm", llmStatus);
        if (!llmStatus.isHealthy()) {
            allHealthy = false;
            errorMsg.append("LLM: ").append(llmStatus.getMessage()).append("; ");
        }

        // 4. 检查 Embedding Model
        ComponentStatus embeddingStatus = checkEmbedding();
        statuses.put("embedding", embeddingStatus);
        if (!embeddingStatus.isHealthy()) {
            allHealthy = false;
            errorMsg.append("Embedding: ").append(embeddingStatus.getMessage()).append("; ");
        }

        return HealthCheckResult.builder()
                .ready(allHealthy)
                .milvusConnected(milvusStatus.isHealthy())
                .neo4jConnected(neo4jStatus.isHealthy())
                .llmConnected(llmStatus.isHealthy())
                .componentStatuses(statuses)
                .errorMessage(allHealthy ? null : errorMsg.toString())
                .build();
    }

    /**
     * 检查 Milvus 连接
     */
    private ComponentStatus checkMilvus() {
        long start = System.currentTimeMillis();
        try {
            boolean exists = collectionInitService.isCollectionReady();
            long latency = System.currentTimeMillis() - start;
            if (exists) {
                return ComponentStatus.builder()
                        .healthy(true)
                        .message("Milvus 连接正常，集合已就绪")
                        .latencyMs(latency)
                        .build();
            } else {
                return ComponentStatus.builder()
                        .healthy(false)
                        .message("Milvus 集合未创建")
                        .latencyMs(latency)
                        .build();
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[RagHealthChecker] Milvus 连接失败", e);
            return ComponentStatus.builder()
                    .healthy(false)
                    .message("Milvus 连接失败: " + e.getMessage())
                    .latencyMs(latency)
                    .build();
        }
    }

    /**
     * 检查 Neo4j 连接
     */
    private ComponentStatus checkNeo4j() {
        long start = System.currentTimeMillis();
        try {
            // 尝试查询节点来验证连接
            neo4jService.searchNodesByName("test");
            long latency = System.currentTimeMillis() - start;
            return ComponentStatus.builder()
                    .healthy(true)
                    .message("Neo4j 连接正常")
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[RagHealthChecker] Neo4j 连接失败", e);
            return ComponentStatus.builder()
                    .healthy(false)
                    .message("Neo4j 连接失败: " + e.getMessage())
                    .latencyMs(latency)
                    .build();
        }
    }

    /**
     * 检查 LLM 连接
     */
    private ComponentStatus checkLlm() {
        long start = System.currentTimeMillis();
        try {
            // 发送简单请求验证 LLM 连接
            String response = chatModel.chat("你好，请回复OK");
            long latency = System.currentTimeMillis() - start;
            if (response != null && !response.isBlank()) {
                return ComponentStatus.builder()
                        .healthy(true)
                        .message("LLM 连接正常")
                        .latencyMs(latency)
                        .build();
            } else {
                return ComponentStatus.builder()
                        .healthy(false)
                        .message("LLM 响应为空")
                        .latencyMs(latency)
                        .build();
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[RagHealthChecker] LLM 连接失败", e);
            return ComponentStatus.builder()
                    .healthy(false)
                    .message("LLM 连接失败: " + e.getMessage())
                    .latencyMs(latency)
                    .build();
        }
    }

    /**
     * 检查 Embedding Model 连接
     */
    private ComponentStatus checkEmbedding() {
        long start = System.currentTimeMillis();
        try {
            var result = embeddingModel.embed("test");
            long latency = System.currentTimeMillis() - start;
            if (result != null && result.content() != null) {
                return ComponentStatus.builder()
                        .healthy(true)
                        .message("Embedding Model 连接正常")
                        .latencyMs(latency)
                        .build();
            } else {
                return ComponentStatus.builder()
                        .healthy(false)
                        .message("Embedding Model 响应为空")
                        .latencyMs(latency)
                        .build();
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[RagHealthChecker] Embedding Model 连接失败", e);
            return ComponentStatus.builder()
                    .healthy(false)
                    .message("Embedding Model 连接失败: " + e.getMessage())
                    .latencyMs(latency)
                    .build();
        }
    }

    /**
     * 获取最后一次检查结果
     */
    public HealthCheckResult getLastCheckResult() {
        return lastCheckResult;
    }

    /**
     * 系统是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 系统是否就绪
     */
    public boolean isReady() {
        return initialized && lastCheckResult != null && lastCheckResult.isReady();
    }
}
