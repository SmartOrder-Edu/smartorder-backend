package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.memory.ConversationCompressService;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 自实现 ReAct 循环引擎
 *
 * 不使用 AiServices 自动工具循环，手动控制每一步：
 * LLM 调用 → 检测工具请求 → 执行工具 → 推送事件 → 继续循环 → 输出最终回答
 *
 * 最大工具调用轮数：5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfManagedReActEngine {

    private static final int MAX_TOOL_ROUNDS = 5;

    private final ChatLanguageModel chatLanguageModel;
    private final LangChain4jToolAdapter toolAdapter;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;
    private final ConversationCompressService compressService;

    /**
     * 执行 ReAct 循环
     */
    public void execute(
            AgentChatRequest request,
            IntentRouteResult routeResult,
            AgentRuntimeContext runtimeContext,
            SseEmitter emitter
    ) {
        // 1. 保存用户消息到 DB
        memoryService.saveUserMessage(
                runtimeContext.getConversationId(), request.getUserId(), request.getMessage());

        // 2. 从记忆系统构建消息列表（含历史上下文）
        List<ChatMessage> messages = memoryService.buildMessages(
                runtimeContext.getConversationId(), request.getUserId(), request.getMessage());

        // 3. 构建工具规范
        List<ToolSpecification> tools = toolAdapter.buildToolSpecifications(routeResult.getRequiredTools());

        // 4. 推送初始思考
        dispatcher.emit(emitter, runtimeContext, AgentEventType.THINK_DELTA, Map.of(
                "text", buildInitialThink(routeResult)
        ));

        // 5. ReAct 循环
        String finalAnswer = null;

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            log.info("[ReAct] 第{}轮开始, traceId={}, tools={}",
                    round, runtimeContext.getTraceId(), routeResult.getRequiredTools());

            ChatResponse response;
            try {
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(tools.isEmpty() ? null : tools)
                        .build();
                response = chatLanguageModel.chat(chatRequest);
            } catch (Exception e) {
                log.error("[ReAct] LLM 调用失败, round={}", round, e);
                dispatcher.emit(emitter, runtimeContext, AgentEventType.ERROR, Map.of(
                        "message", "模型调用失败: " + e.getMessage()
                ));
                return;
            }

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            // 6. 检查是否有工具调用
            if (aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    if (round > MAX_TOOL_ROUNDS) {
                        log.warn("[ReAct] 工具调用轮数超过上限 {}", MAX_TOOL_ROUNDS);
                        dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                                "text", "工具调用已达上限，我将基于已有信息继续回答。"
                        ));
                        break;
                    }

                    ToolInvokeContext toolContext = ToolInvokeContext.builder()
                            .userId(request.getUserId())
                            .conversationId(runtimeContext.getConversationId())
                            .traceId(runtimeContext.getTraceId())
                            .mode(runtimeContext.getMode())
                            .runtimeContext(runtimeContext)
                            .emitter(emitter)
                            .agentName("MainReActAgent")
                            .build();

                    try {
                        String toolResult = toolAdapter.execute(toolRequest, toolContext);

                        // 保存工具消息到 DB
                        memoryService.saveToolMessage(
                                runtimeContext.getConversationId(), request.getUserId(),
                                toolRequest.name(), toolResult);

                        messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));

                        dispatcher.emit(emitter, runtimeContext, AgentEventType.THINK_DELTA, Map.of(
                                "text", "我已获取工具结果，正在结合数据继续分析。"
                        ));

                    } catch (Exception e) {
                        log.error("[ReAct] 工具执行失败: tool={}", toolRequest.name(), e);
                        messages.add(ToolExecutionResultMessage.from(toolRequest,
                                "工具执行失败: " + e.getMessage()));
                    }
                }

                continue;
            }

            // 7. 无工具调用 → 输出最终回答
            String answer = aiMessage.text();
            if (answer != null && !answer.isBlank()) {
                dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                        "text", answer
                ));
                finalAnswer = answer;
            }

            log.info("[ReAct] 完成, traceId={}, rounds={}", runtimeContext.getTraceId(), round);
            break;
        }

        // 8. 达到最大轮数仍未产出回答
        if (finalAnswer == null) {
            finalAnswer = "我已经进行了多轮分析。建议你把问题拆成更小的部分，我可以继续帮你处理。";
            log.warn("[ReAct] 达到最大工具调用轮数, traceId={}", runtimeContext.getTraceId());
            dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                    "text", finalAnswer
            ));
        }

        // 9. 保存助手回复
        memoryService.saveAssistantMessage(
                runtimeContext.getConversationId(), request.getUserId(), finalAnswer);

        // 10. 异步检查是否需要压缩
        compressService.checkAndCompress(runtimeContext.getConversationId(), request.getUserId());
    }

    // ============ Private Methods ============

    private String buildInitialThink(IntentRouteResult routeResult) {
        if ("简单".equals(routeResult.getComplexity())) {
            return "这是一个基础问题，我会直接解释核心概念。";
        }
        if (routeResult.getRequiredTools() != null && !routeResult.getRequiredTools().isEmpty()) {
            return "这是一个复杂学习问题，我会调用" + routeResult.getRequiredTools().size()
                    + "个工具了解你的学习情况，再给出针对性解答。";
        }
        return "这是一个学习问题，我会结合相关知识帮你分析。";
    }
}
