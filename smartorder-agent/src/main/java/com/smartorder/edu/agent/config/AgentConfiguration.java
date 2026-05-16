package com.smartorder.edu.agent.config;

import com.smartorder.edu.agent.http.MimoHttpClientBuilder;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 配置：手动创建 LLM Bean + 对话记忆管理
 */
@Configuration
public class AgentConfiguration {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        // 使用 MimoHttpClientBuilder 禁用 MIMO thinking mode，
        // 解决 LangChain4j 1.0.0-beta3 AiMessage 不支持 reasoningContent 导致的多轮对话 400 错误
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .httpClientBuilder(new MimoHttpClientBuilder())
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .httpClientBuilder(new MimoHttpClientBuilder())
                .build();
    }

    // ========== 对话记忆管理 ==========

    private final Map<String, ChatMemory> memoryStore = new ConcurrentHashMap<>();

    public ChatMemory getOrCreateMemory(String conversationId) {
        return memoryStore.computeIfAbsent(conversationId,
                id -> MessageWindowChatMemory.withMaxMessages(20));
    }

    public void clearMemory(String conversationId) {
        memoryStore.remove(conversationId);
    }
}
