package com.smartorder.edu.common.config;



import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LlmConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.log-requests:true}")
    private boolean logRequests;

    @Value("${langchain4j.open-ai.chat-model.log-responses:true}")
    private boolean logResponses;

    @Value("${langchain4j.open-ai.embedding-model.api-key:${langchain4j.open-ai.chat-model.api-key}}")
    private String embeddingApiKey;

    @Value("${langchain4j.open-ai.embedding-model.base-url:${langchain4j.open-ai.chat-model.base-url}}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.open-ai.embedding-model.model-name:bge-m3}")
    private String embeddingModelName;

    @Value("${langchain4j.open-ai.embedding-model.dimensions:1024}")
    private int embeddingDimensions;

    @Bean
    public OpenAiChatModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .timeout(Duration.ofSeconds(120)) // 文档解析较慢，建议延长超时时间
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .dimensions(embeddingDimensions)
                .build();
    }
}