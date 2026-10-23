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

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

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

    /**
     * 标题模型是否显式关闭思考。
     *
     * <p><b>为什么必须显式关</b>：DeepSeek v4 系列默认就带思考，思考 token 与
     * {@code max_tokens} 共用一个预算。实测 {@code deepseek-v4-flash} 在
     * {@code max_tokens=64} 时，64 个 token 全部落在 {@code reasoning_tokens} 上，
     * 返回 {@code content=""}、{@code finish_reason="length"}——标题直接生成失败。
     * 这类失败是随机的（输入越绕，思考越长），表现为"有时候标题没变"。</p>
     *
     * <p>如果换成不支持该参数的服务商（如 MIMO），把
     * {@code agent.llm.title-disable-thinking} 设为 {@code false}。</p>
     */
    @Value("${agent.llm.title-disable-thinking:true}")
    private boolean titleDisableThinking;

    /**
     * 标题模型名——可以与主模型分开。
     *
     * <p>标题只输出十几个字，不需要主模型那么强的推理能力；单独配置也方便
     * 在不动主链路的情况下换模型试效果。留空则沿用主模型名。</p>
     */
    @Value("${agent.llm.title-model-name:}")
    private String titleModelName;

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

    @Bean
    public ChatModel profileUpdateChatModel() {
        return OpenAiChatModel.builder()
                .httpClientBuilder(JdkLangChain4jHttpClientBuilder.withDefaults())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
//                .returnThinking(true)
//                .sendThinking(true, "reasoning_content")
//                新的方式
//                .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)
//                .strictJsonSchema(true)
//                老的方式
//                .responseFormat("json_object")
                .responseFormat("json_object")
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

    /**
     * 会话标题生成专用 ChatModel — 禁用 thinking、限制输出长度、低温度。
     * <p>
     * 为什么不能复用 {@link #chatLanguageModel()}：它开了 {@code returnThinking(true)}，
     * MIMO 类模型在启用 reasoning 时可能把推理文本混进 content，导致标题里混入分析过程；
     * 且标题只需要十几个字，用主模型既慢又浪费。
     * <p>
     * <b>必须显式下发 {@code thinking.type=disabled}</b>：不设 {@code returnThinking}
     * 只是"不读取"推理内容，并不能阻止服务端思考。DeepSeek v4 默认思考，
     * 思考 token 会吃满 {@code maxTokens} 让 content 变成空串（见 {@link #titleDisableThinking}）。
     * <p>
     * {@code temperature(0.3)}：标题要求"同一个问题永远得到同一个标签"，
     * 默认温度下同一句话两次可能给出"Milvus连接超时排查"和"Milvus超时问题定位"两种写法，
     * 列表里风格就散了。低温度让输出稳定收敛到提示词给的标签形态。
     */
    @Bean("titleChatModel")
    public ChatModel titleChatModel() {
        return OpenAiChatModel.builder()
                .httpClientBuilder(JdkLangChain4jHttpClientBuilder.withDefaults())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(titleModelName == null || titleModelName.isBlank() ? modelName : titleModelName)
                .temperature(0.3)
                .maxTokens(128)
                .customParameters(titleDisableThinking
                        ? Map.of("thinking", Map.of("type", "disabled"))
                        : Map.of())
                .timeout(Duration.ofSeconds(20))
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

    @Bean
    public StreamingChatModel streamingQuestionChatModel() {
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(JdkLangChain4jHttpClientBuilder.withDefaults())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .responseFormat("json_object")
                .returnThinking(false)
                .sendThinking(false, "reasoning_content")
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
