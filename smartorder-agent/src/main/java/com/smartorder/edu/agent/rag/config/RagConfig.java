package com.smartorder.edu.agent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.rag")
public class RagConfig {

    /**
     * 是否启用 RAG 系统
     */
    private boolean enabled = true;

    /**
     * 默认返回结果数量
     */
    private int defaultTopK = 10;

    /**
     * 最大返回结果数量
     */
    private int maxTopK = 20;

    /**
     * 默认检索模式：auto/simple/medium/complex
     */
    private String defaultRetrievalMode = "auto";

    /**
     * 查询分析配置
     */
    private AnalysisConfig analysis = new AnalysisConfig();

    /**
     * 检索配置
     */
    private RetrievalConfig retrieval = new RetrievalConfig();

    /**
     * 评估配置
     */
    private EvaluationConfig evaluation = new EvaluationConfig();

    /**
     * 降级配置
     */
    private DegradeConfig degrade = new DegradeConfig();

    @Data
    public static class AnalysisConfig {
        /**
         * 是否启用 LLM 查询分析
         */
        private boolean llmEnabled = true;

        /**
         * 简单查询复杂度阈值（低于此值走简单检索）
         */
        private double simpleThreshold = 0.3;

        /**
         * 复杂查询复杂度阈值（高于此值走复杂检索）
         */
        private double complexThreshold = 0.7;
    }

    @Data
    public static class RetrievalConfig {
        /**
         * 简单检索：向量检索权重
         */
        private double vectorWeight = 0.7;

        /**
         * 简单检索：关键词检索权重
         */
        private double keywordWeight = 0.3;

        /**
         * 图检索最大跳数
         */
        private int maxGraphHops = 3;

        /**
         * 图检索超时时间（毫秒）
         */
        private long graphTimeoutMs = 5000;

        /**
         * 组合检索：Round-Robin 交替数量
         */
        private int roundRobinCount = 5;
    }

    @Data
    public static class EvaluationConfig {
        /**
         * 是否启用 RAG 评估
         */
        private boolean enabled = true;

        /**
         * 评估通过阈值
         */
        private double passThreshold = 0.6;

        /**
         * 上下文相关性权重
         */
        private double contextRelevanceWeight = 0.4;

        /**
         * 可信度权重
         */
        private double faithfulnessWeight = 0.3;

        /**
         * 答案相关性权重
         */
        private double answerRelevanceWeight = 0.3;
    }

    @Data
    public static class DegradeConfig {
        /**
         * 是否启用自动降级
         */
        private boolean enabled = true;

        /**
         * 复杂检索失败后降级到中等检索
         */
        private boolean complexToMedium = true;

        /**
         * 中等检索失败后降级到简单检索
         */
        private boolean mediumToSimple = true;

        /**
         * 简单检索失败后是否告警
         */
        private boolean alertOnSimpleFailure = true;
    }
}
