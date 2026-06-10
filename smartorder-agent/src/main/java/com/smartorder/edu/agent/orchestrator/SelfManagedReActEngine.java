package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.config.AgentConfiguration;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.memory.ConversationCompressService;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 自实现 ReAct 循环引擎
 *
 * 不使用 AiServices 自动工具循环，手动控制每一步：
 * LLM 调用 → 检测工具请求 → 执行工具 → 推送事件 → 继续循环 → 流式输出最终回答
 *
 * 最大工具调用轮数：5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfManagedReActEngine {

    private static final int MAX_TOOL_ROUNDS = 5;

    private final AgentConfiguration agentConfiguration;
    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatLanguageModel;
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
        // 1. 从记忆系统构建历史上下文（已包含当前用户消息，因为 orchestrator 在调用引擎前已落库）
        List<ChatMessage> messages = memoryService.buildMessages(
                runtimeContext.getConversationId(), request.getUserId());
        if (runtimeContext.getAttachmentContext() != null && !runtimeContext.getAttachmentContext().isBlank()) {
            messages.add(Math.min(1, messages.size()), SystemMessage.from(runtimeContext.getAttachmentContext()));
        }

        // 3. 构建工具规范
        List<ToolSpecification> tools = toolAdapter.buildToolSpecifications(routeResult.getRequiredTools());

        // 4. ReAct 循环 — 每轮非流式 LLM 返回的 AiMessage.thinking() 即为真实思考过程
        String finalAnswer = null;
        StringBuilder allThinkingBuilder = new StringBuilder();

        // 追踪思考分段（与工具调用交错），用于前端还原实时展示效果
        List<Map<String, Object>> reasoningSegments = new ArrayList<>();
        StringBuilder currentThinkingBuilder = new StringBuilder();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            log.info("[ReAct] 第{}轮开始, traceId={}, tools={}",
                    round, runtimeContext.getTraceId(), routeResult.getRequiredTools());

            ChatResponse response;
            try {
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(messages)
                        .parameters(OpenAiChatRequestParameters.builder()
                                .toolSpecifications(tools.isEmpty() ? null : tools)
                                .customParameters(agentConfiguration.buildThinkingCustomParameters())
                                .build())
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

            // 发射 LLM 真实的思考过程（MIMO 的 reasoning_content）
            String thinking = aiMessage.thinking();
            if (thinking != null && !thinking.isBlank()) {
                dispatcher.emit(emitter, runtimeContext, AgentEventType.THINK_DELTA, Map.of(
                        "text", thinking
                ));
                allThinkingBuilder.append(thinking);
                currentThinkingBuilder.append(thinking);
            }

            // 5. 检查是否有工具调用
            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);

                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                for (int i = 0; i < toolRequests.size(); i++) {
                    ToolExecutionRequest toolRequest = toolRequests.get(i);

                    // 第一个工具挂载到当前思考分段，后续工具创建空思考分段
                    Map<String, Object> seg = new HashMap<>();
                    seg.put("thinkingText", i == 0 ? currentThinkingBuilder.toString() : "");
                    currentThinkingBuilder.setLength(0);

                    ToolInvokeContext toolContext = ToolInvokeContext.builder()
                            .userId(request.getUserId())
                            .conversationId(runtimeContext.getConversationId())
                            .traceId(runtimeContext.getTraceId())
                            .mode(runtimeContext.getMode())
                            .runtimeContext(runtimeContext)
                            .emitter(emitter)
                            .agentName("MainReActAgent")
                            .build();

                    long toolStart = System.currentTimeMillis();
                    try {
                        String toolResult = toolAdapter.execute(toolRequest, toolContext);
                        long durationMs = System.currentTimeMillis() - toolStart;

                        Map<String, Object> toolCall = new HashMap<>();
                        toolCall.put("toolName", toolRequest.name());
                        toolCall.put("status", "completed");
                        toolCall.put("arguments", toolRequest.arguments());
                        toolCall.put("result", toolResult);
                        toolCall.put("durationMs", durationMs);
                        seg.put("toolCall", toolCall);

                        // 保存工具消息到 DB
                        memoryService.saveToolMessage(
                                runtimeContext.getConversationId(), request.getUserId(),
                                toolRequest.name(), toolResult);

                        messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));

                    } catch (Exception e) {
                        log.error("[ReAct] 工具执行失败: tool={}", toolRequest.name(), e);
                        long durationMs = System.currentTimeMillis() - toolStart;

                        Map<String, Object> toolCall = new HashMap<>();
                        toolCall.put("toolName", toolRequest.name());
                        toolCall.put("status", "failed");
                        toolCall.put("arguments", toolRequest.arguments());
                        toolCall.put("durationMs", durationMs);
                        seg.put("toolCall", toolCall);

                        messages.add(ToolExecutionResultMessage.from(toolRequest,
                                "工具执行失败: " + e.getMessage()));
                    }

                    reasoningSegments.add(seg);
                }

                continue;
            }

            // 6. 无工具调用 → 使用流式模型逐 token 输出最终回答
            String[] result = streamFinalAnswer(messages, tools, emitter, runtimeContext,
                    allThinkingBuilder, currentThinkingBuilder, reasoningSegments);
            finalAnswer = result[0];

            log.info("[ReAct] 完成, traceId={}, rounds={}", runtimeContext.getTraceId(), round);
            break;
        }

        // 7. 达到最大轮数仍未产出回答
        if (finalAnswer == null) {
            finalAnswer = "我已经进行了多轮分析。建议你把问题拆成更小的部分，我可以继续帮你处理。";
            log.warn("[ReAct] 达到最大工具调用轮数, traceId={}", runtimeContext.getTraceId());
            dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                    "text", finalAnswer
            ));
        }

        // 8. 保存助手回复（含 reasoning_content + 思考分段，用于前端还原实时展示效果）
        String reasoningContent = allThinkingBuilder.length() > 0 ? allThinkingBuilder.toString() : null;
        String segmentsJson = reasoningSegments.isEmpty() ? null : JSON.toJSONString(reasoningSegments);
        memoryService.saveAssistantMessage(
                runtimeContext.getConversationId(), request.getUserId(), finalAnswer, reasoningContent,
                runtimeContext.getTraceId(), segmentsJson);

        // 9. 异步检查是否需要压缩
        compressService.checkAndCompress(runtimeContext.getConversationId(), request.getUserId());
    }

    /**
     * 使用流式模型输出最终回答，逐 token 推送 agent.text.delta 事件，
     * 同时通过 onPartialThinking 推送真实推理过程。
     *
     * @return String[2]: [0]=回答文本, [1]=reasoning_content (可能为 null)
     */
    private String[] streamFinalAnswer(
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            SseEmitter emitter,
            AgentRuntimeContext runtimeContext,
            StringBuilder allThinkingBuilder,
            StringBuilder currentThinkingBuilder,
            List<Map<String, Object>> reasoningSegments
    ) {
        StringBuilder answerBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(OpenAiChatRequestParameters.builder()
                        .toolSpecifications(tools.isEmpty() ? null : tools)
                        .customParameters(agentConfiguration.buildThinkingCustomParameters())
                        .build())
                .build();

        streamingChatLanguageModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (partialThinking != null && partialThinking.text() != null) {
                    thinkingBuilder.append(partialThinking.text());
                    allThinkingBuilder.append(partialThinking.text());
                    currentThinkingBuilder.append(partialThinking.text());
                    dispatcher.emit(emitter, runtimeContext, AgentEventType.THINK_DELTA, Map.of(
                            "text", partialThinking.text()
                    ));
                }
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    answerBuilder.append(partialResponse);
                    dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                            "text", partialResponse
                    ));
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                log.error("[ReAct] 流式输出失败, traceId={}", runtimeContext.getTraceId(), error);
                dispatcher.emit(emitter, runtimeContext, AgentEventType.ERROR, Map.of(
                        "message", "流式输出失败: " + error.getMessage()
                ));
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[ReAct] 流式输出超时, traceId={}", runtimeContext.getTraceId());
                dispatcher.emit(emitter, runtimeContext, AgentEventType.ERROR, Map.of(
                        "message", "响应超时，请重试"
                ));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ReAct] 流式输出被中断, traceId={}", runtimeContext.getTraceId());
        }

        // 流式阶段结束后，将剩余的思考文本作为最后一个分段（无工具调用）
        if (currentThinkingBuilder.length() > 0) {
            Map<String, Object> finalSeg = new HashMap<>();
            finalSeg.put("thinkingText", currentThinkingBuilder.toString());
            reasoningSegments.add(finalSeg);
            currentThinkingBuilder.setLength(0);
        }

        return new String[]{
                answerBuilder.toString(),
                thinkingBuilder.length() > 0 ? thinkingBuilder.toString() : null
        };
    }
}
