package com.smartorder.edu.common.config;


import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    // 根据你的截图，指定数据库名称为 smart_order_db
    private static final String DATABASE_NAME = "smart_order_db";

    @Bean
    public MilvusClientV2 milvusClientV2() {
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(DATABASE_NAME)
                .build();
        return new MilvusClientV2(connectConfig);
    }
}
