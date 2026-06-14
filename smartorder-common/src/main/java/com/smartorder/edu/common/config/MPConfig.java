package com.smartorder.edu.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class MPConfig {

    @Bean
    public MybatisPlusInterceptor pmInterceptor() {
        MybatisPlusInterceptor mpInterceptor = new MybatisPlusInterceptor();
        mpInterceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        mpInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return mpInterceptor;
    }

    /**
     * MySQL 事务管理器——设为 @Primary，确保 @Transactional 默认走 MySQL 而非 Neo4j
     *
     * Spring Data Neo4j 自动配置会创建 Neo4jTransactionManager，
     * 导致 DataSourceTransactionManagerAutoConfiguration 的
     * @ConditionalOnMissingBean 不满足而跳过 MySQL 事务管理器注册。
     * 显式创建 @Primary 的 DataSourceTransactionManager 解决此问题。
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

}
