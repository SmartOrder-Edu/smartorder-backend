package com.smartorder.edu.agent.tool;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LangChain4j 工具适配器——将 ToolHandler 桥接到 LangChain4j 工具协议
 *
 * 职责：
 * 1. 将 ToolHandler → ToolSpecification（含参数 JSON Schema，供 LLM 识别可用工具和参数结构）
 * 2. 将 ToolExecutionRequest → 委托给 TracingToolExecutor 执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jToolAdapter {

    private final ToolRegistry toolRegistry;
    private final TracingToolExecutor tracingToolExecutor;

    /**
     * 根据工具名列表构建 LangChain4j ToolSpecification 列表
     *
     * 每个规格包含工具 name、description 和参数 JSON Schema，
     * 确保模型知道每个工具需要哪些参数。
     */
    public List<ToolSpecification> buildToolSpecifications(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }

        return toolNames.stream()
                .map(toolRegistry::get)
                .map(handler -> {
                    ToolSpecification.Builder specBuilder = ToolSpecification.builder()
                            .name(handler.name())
                            .description(handler.description());

                    // 从 requestType() 生成参数 JSON Schema
                    JsonObjectSchema parameterSchema = buildParameterSchema(handler.requestType());
                    if (parameterSchema != null) {
                        specBuilder.parameters(parameterSchema);
                    }

                    return specBuilder.build();
                })
                .toList();
    }

    /**
     * 执行工具调用请求
     */
    public String execute(ToolExecutionRequest request, ToolInvokeContext context) {
        ToolHandler handler = toolRegistry.get(request.name());

        // 将 JSON 参数解析为工具定义的请求类型
        Object arguments = parseArguments(request.arguments(), handler.requestType());

        // 通过安全层执行
        Object result = tracingToolExecutor.execute(handler, arguments, context);

        // 处理结构化返回
        if (result instanceof ToolResult toolResult) {
            return toolResult.toLlmContext();
        }

        // 返回结果 JSON 字符串
        return result instanceof String ? (String) result : JSON.toJSONString(result);
    }

    /**
     * 从 Java 类生成 JsonObjectSchema，供 ToolSpecification 使用。
     * 利用 LangChain4j 内置的 JsonSchemaElementHelper 从 requestType 的字段自动推导。
     */
    private JsonObjectSchema buildParameterSchema(Class<?> requestType) {
        try {
            JsonSchemaElement element = JsonSchemaElementUtils.jsonSchemaElementFrom(requestType);
            if (element instanceof JsonObjectSchema objectSchema) {
                return objectSchema;
            }
            // 如果不是直接的 JsonObjectSchema（可能是引用类型），手动包装
            // jsonSchemaElementFrom 对于普通 POJO 通常返回 JsonObjectSchema
            return null;
        } catch (Exception e) {
            log.warn("[ToolAdapter] 无法为 {} 生成 JSON Schema: {}", requestType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 LLM 传来的 JSON 参数解析为工具定义的强类型请求对象
     *
     * 解析失败时返回工具调用失败信息，而不是静默构造空对象，
     * 让模型有机会修正参数重新调用。
     */
    private Object parseArguments(String argumentsJson, Class<?> requestType) {
        try {
            if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson)) {
                // 空参数可能是合法的（工具无必填参数），先尝试默认构造
                return requestType.getDeclaredConstructor().newInstance();
            }
            return JSON.parseObject(argumentsJson, requestType);
        } catch (Exception e) {
            log.warn("[ToolAdapter] 参数解析失败: json={}, type={}, error={}",
                    argumentsJson, requestType.getSimpleName(), e.getMessage());
            // 不再静默构造空对象，抛出异常让 TracingToolExecutor 记录 TOOL_FAILED
            throw new IllegalArgumentException(
                    "工具参数解析失败，请检查参数格式。期望类型: " + requestType.getSimpleName()
                            + "，收到: " + argumentsJson, e);
        }
    }
}
