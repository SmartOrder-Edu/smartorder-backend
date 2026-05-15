package com.smartorder.edu.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * RestTemplate 配置类

 */
@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(600000);        // 连接超时（毫秒），10分钟
        factory.setReadTimeout(600000);           // 读取超时（毫秒），10分钟
        
        RestTemplate restTemplate = new RestTemplate(factory);
        
        // 设置字符串消息转换器的编码为 UTF-8，确保中文正确传输
        restTemplate.getMessageConverters().stream()
            .filter(converter -> converter instanceof StringHttpMessageConverter)
            .findFirst()
            .ifPresent(converter -> ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8));
        
        return restTemplate;
    }
}
