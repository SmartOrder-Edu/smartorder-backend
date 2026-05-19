package com.smartorder.edu.common.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class PromptConfig {

    // 从 resources 目录下读取提示词文件
    @Value("classpath:prompts/markdown_normalize.txt")
    private Resource markdownNormalizePromptResource;

    @Bean
    public String markdownNormalizePromptTemplate() throws IOException {
        return new String(markdownNormalizePromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}