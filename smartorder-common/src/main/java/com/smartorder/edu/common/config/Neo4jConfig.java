package com.smartorder.edu.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Neo4j 配置类
 */
@Configuration
@EnableNeo4jRepositories(basePackages = "com.smartorder.edu.common.mapper")
@EnableTransactionManagement
public class Neo4jConfig {
    // Spring Boot 自动配置 Neo4j，无需额外配置
    // 连接信息从 application.yml 中读取
}
