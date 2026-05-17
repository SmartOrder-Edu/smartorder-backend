package com.smartorder.edu.agent.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 配置：LLM Bean + 对话记忆管理
 *
 * LangChain4j 1.13.0 已原生支持 AiMessage.thinking() 和 onPartialThinking()，
 * 不再需要禁用 MIMO thinking mode。使用 @Primary 确保手动 Bean 优先于自动配置。
 */
@Configuration
public class AgentConfiguration {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Primary
    @Bean
    public ChatModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    @Primary
    @Bean
    public StreamingChatModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
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
