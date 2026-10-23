package com.smartorder.edu.common.config;

/**
 * Milvus 相关配置。
 *
 * <p>注意：{@code MilvusClientV2} 不再以 {@code @Bean} 形式在容器启动时创建。</p>
 *
 * <p>原实现直接在 {@code @Bean} 工厂方法里 {@code new MilvusClientV2(...)}，其构造器会
 * 同步发起 gRPC connect。一旦 Milvus 不可达或建连耗时超过 SDK 内部超时，Bean 创建失败会
 * 沿 {@code KnowledgeBaseController → KnowledgeBaseServiceImpl → VectorIndexService →
 * MilvusClientV2} 的依赖链冒泡，导致整个 Spring 容器启动失败。</p>
 *
 * <p>现改为由 {@link MilvusClientProvider} 懒连接 + 失败降级 + 自动重连，
 * 使用方通过 {@code milvusClientProvider.get()} 获取客户端，
 * 返回 {@code null} 时按"向量能力不可用"降级处理。相关配置项见
 * {@code application-common.yml} 中的 {@code milvus.*}。</p>
 */
public class MilvusConfig {

    private MilvusConfig() {
    }
}
