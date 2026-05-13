package com.smartorder.edu.agent.tool;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j 工具适配器——将 ToolHandler 桥接到 LangChain4j 工具协议
 *
 * 职责：
 * 1. 将 ToolHandler → ToolSpecification（供 LLM 识别可用工具）
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
     */
    public List<ToolSpecification> buildToolSpecifications(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }

        return toolNames.stream()
                .map(toolRegistry::get)
                .map(handler -> ToolSpecification.builder()
                        .name(handler.name())
                        .description(handler.description())
                        .build())
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

        // 返回结果 JSON 字符串
        return result instanceof String ? (String) result : JSON.toJSONString(result);
    }

    /**
     * 将 LLM 传来的 JSON 参数解析为工具定义的强类型请求对象
     */
    private Object parseArguments(String argumentsJson, Class<?> requestType) {
        try {
            if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson)) {
                return requestType.getDeclaredConstructor().newInstance();
            }
            return JSON.parseObject(argumentsJson, requestType);
        } catch (Exception e) {
            log.warn("[ToolAdapter] 参数解析失败，使用默认构造: json={}, type={}", argumentsJson, requestType.getSimpleName());
            try {
                return requestType.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new IllegalArgumentException("工具参数解析失败: " + argumentsJson, e);
            }
        }
    }
}
