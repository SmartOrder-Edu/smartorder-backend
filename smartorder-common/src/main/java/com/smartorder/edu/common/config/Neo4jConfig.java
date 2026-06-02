package com.smartorder.edu.common.config;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Neo4j 配置类
 */
@Configuration
@EnableNeo4jRepositories(basePackages = "com.smartorder.edu.common.mapper")
@EnableTransactionManagement
public class Neo4jConfig {

    /**
     * Neo4j 事务管理器
     * 由于 MPConfig 中 @Primary 的 DataSourceTransactionManager 覆盖了自动配置，
     * 需要显式创建 Neo4jTransactionManager
     */
    @Bean
    public Neo4jTransactionManager neo4jTransactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }
}
