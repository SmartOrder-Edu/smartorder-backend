package com.smartorder.edu.common.config;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class WebSearchToolConfig {

    // 初始化 Tavily 引擎，正确读取配置文件中的 API Key
    @Bean
    public WebSearchEngine tavilySearchEngine(@Value("${tavily.api.api-key}") String apiKey) {
        return TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .searchDepth("advanced")
                .includeAnswer(true)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

}
