package com.smartorder.edu.agent.config;

import com.smartorder.edu.common.config.JdkLangChain4jHttpClientBuilder;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent LLM beans and conversation memory.
 *
 * <p>LLM calls explicitly use the JDK HTTP client. Do not route them through
 * Spring RestClient: when Jetty is present on the classpath, Spring Boot can
 * auto-select Jetty and trigger its default 30s idle timeout.
 */
@Configuration
public class AgentConfiguration {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${agent.llm.thinking-mode:disabled}")
    private String thinkingMode;

    @Value("${agent.llm.reasoning-effort:high}")
    private String reasoningEffort;

    public Map<String, Object> buildThinkingCustomParameters() {
        if (!"deepseek-v4".equalsIgnoreCase(thinkingMode)) {
            return Map.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("thinking", Map.of("type", "enabled"));
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            params.put("reasoning_effort", reasoningEffort);
        }
        return params;
    }

    @Primary
    @Bean
    public ChatModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .httpClientBuilder(JdkLangChain4jHttpClientBuilder.withDefaults())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .returnThinking(true)
                .sendThinking(true, "reasoning_content")
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 规划专用 ChatModel — 禁用 thinking，确保 PlannerAgent 返回纯 JSON。
     * <p>
     * MIMO 模型在启用 reasoning_content 时可能把推理文本混入 content 字段，
     * 导致 PlannerAgent 输出的 JSON 不可解析。规划场景不需要思考过程展示，
     * 使用此 Bean 可获得稳定的结构化输出。
     */
    @Bean
    public ChatModel planningChatModel() {
        return OpenAiChatModel.builder()
                .httpClientBuilder(JdkLangChain4jHttpClientBuilder.withDefaults())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .responseFormat("json_object")
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Primary
    @Bean
    public StreamingChatModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(JdkLangChain4jHttpClientBuilder.withDefaults())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .returnThinking(true)
                .sendThinking(true, "reasoning_content")
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private final Map<String, ChatMemory> memoryStore = new ConcurrentHashMap<>();

    public ChatMemory getOrCreateMemory(String conversationId) {
        return memoryStore.computeIfAbsent(
                conversationId,
                id -> MessageWindowChatMemory.withMaxMessages(20)
        );
    }

    public void clearMemory(String conversationId) {
        memoryStore.remove(conversationId);
    }
}
