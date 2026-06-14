package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 系统健康检查结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckResult {

    /**
     * 系统是否就绪
     */
    private boolean ready;

    /**
     * Milvus 连接状态
     */
    private boolean milvusConnected;

    /**
     * Neo4j 连接状态
     */
    private boolean neo4jConnected;

    /**
     * LLM 连接状态
     */
    private boolean llmConnected;

    /**
     * 各组件详细状态
     */
    private Map<String, ComponentStatus> componentStatuses;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 组件状态
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentStatus {
        private boolean healthy;
        private String message;
        private long latencyMs;
    }
}
