package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.config.AgentConfiguration;
import com.smartorder.edu.agent.exception.BudgetExceededException;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.dto.IntentRouteResult;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.service.ConversationSummaryService;
import com.smartorder.edu.agent.service.ConversationContextService;
import com.smartorder.edu.agent.service.TraceEventDispatcher;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolRegistry;
import com.smartorder.edu.agent.tool.ToolResult;
import com.smartorder.edu.agent.tool.TracingToolExecutor;
import com.smartorder.edu.agent.util.PromptLoader;
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
import java.util.LinkedHashMap;
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
    private static final ReActRunOptions USER_VISIBLE_OPTIONS =
            new ReActRunOptions(true, true, true);
    private static final ReActRunOptions INTERNAL_TASK_OPTIONS =
            new ReActRunOptions(false, false, false);

    private final AgentConfiguration agentConfiguration;
    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatLanguageModel;
    private final LangChain4jToolAdapter toolAdapter;
    private final TraceEventDispatcher dispatcher;
    private final ConversationContextService contextService;
    private final ConversationSummaryService summaryService;
    private final PromptLoader promptLoader;

    /**
     * 为当前 ReAct 请求构建仅包含实际可用工具描述的系统提示词。
     */
    private SystemMessage buildReActSystemPrompt(List<String> toolNames) {
        Map<String, String> nameToDesc = new LinkedHashMap<>();
        for (String name : toolNames) {
            try {
                var handler = toolAdapter.getToolRegistry().get(name);
                nameToDesc.put(name, handler.description());
            } catch (IllegalArgumentException e) {
                log.warn("[ReAct] 工具未在 ToolRegistry 中注册，跳过: {}", name);
            }
        }
        String prompt = promptLoader.buildPromptWithTools("system/main-agent.md", nameToDesc);
        return SystemMessage.from(prompt);
    }

    private SystemMessage buildSelectedReferenceInstruction() {
        return SystemMessage.from(
                "[系统指令] 当前用户已在界面选择知识库或文件作为本轮回答依据。"
                        + "这些引用资料的检索范围由系统工具层自动绑定，对你不可见。"
                        + "你不需要知道、询问、推测或填写任何内部资料标识。"
                        + "当用户提到\"这份资料\"、\"这个知识库\"、\"上面的文件\"时，"
                        + "默认指代当前已选引用资料。你必须先调用 ragRetrieve 工具，并只围绕用户问题构造 query，"
                        + "再基于检索结果回答；不要编造资料内容。"
        );
    }

    private SystemMessage buildStudyContextInstruction(Map<String, Object> studyContext) {
        String contextJson = JSON.toJSONString(studyContext);
        if (contextJson.length() > 6000) {
            contextJson = contextJson.substring(0, 6000) + "...";
        }
        return SystemMessage.from("""
                [学习执行页上下文]
                用户正在学习一个教学脚本。下面是前端传入的当前学习环境上下文，回答时必须优先围绕它解释。
                如果用户说“这里”“这一步”“没听懂”，默认指当前脚本步骤和当前教学段。
                回答要短、直接、教学化；优先解释当前段落，不要泛泛展开整章内容。可以给出一个小例子或一条临时板书建议。

                上下文 JSON：
                %s
                """.formatted(contextJson));
    }

    private void injectStudyContextIfPresent(List<ChatMessage> messages, AgentRuntimeContext runtimeContext) {
        Map<String, Object> studyContext = runtimeContext.getStudyContext();
        if (studyContext == null || studyContext.isEmpty()) {
            return;
        }
        messages.add(Math.min(1, messages.size()), buildStudyContextInstruction(studyContext));
        log.info("[SelfManagedReActEngine] 注入学习执行页上下文，keys={}", studyContext.keySet());
    }

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
        List<ChatMessage> messages = contextService.buildMessages(
                runtimeContext.getConversationId(), request.getUserId());
        if (runtimeContext.getAttachmentContext() != null && !runtimeContext.getAttachmentContext().isBlank()) {
            messages.add(Math.min(1, messages.size()), SystemMessage.from(runtimeContext.getAttachmentContext()));
        }
        injectStudyContextIfPresent(messages, runtimeContext);

        // 注入 RAG 上下文（自动触发时生成）
        if (runtimeContext.getRagContext() != null && !runtimeContext.getRagContext().isBlank()) {
            messages.add(Math.min(1, messages.size()), SystemMessage.from(runtimeContext.getRagContext()));
            log.info("[SelfManagedReActEngine] 注入 RAG 上下文，长度={}", runtimeContext.getRagContext().length());
        }

        // 注入强制 ragRetrieve 指令（当前端传递了引用资料范围时）
        if (runtimeContext.isForceRagRetrieve()) {
            messages.add(Math.min(1, messages.size()), buildSelectedReferenceInstruction());
            log.info("[SelfManagedReActEngine] 注入强制 ragRetrieve 指令");
        }

        // 2. 替换系统提示词为仅包含当前可用工具的版本
        messages.set(0, buildReActSystemPrompt(routeResult.getRequiredTools()));

        // 3. 构建工具规范
        List<ToolSpecification> tools = toolAdapter.buildToolSpecifications(routeResult.getRequiredTools());

        runReActLoop(request, routeResult, runtimeContext, emitter, messages, tools,
                false, USER_VISIBLE_OPTIONS);
    }

    /**
     * 带任务上下文的 ReAct 执行（PlanAndExecute 引擎调用）
     *
     * @param taskSystemMessage   任务专属系统提示
     * @param previousOutputs     前置任务的输出
     * @param confirmationGranted 是否已通过确认门（允许 WRITE_CONFIRMED 工具）
     * @return 最终回答文本
     */
    public String executeWithTaskContext(
            AgentChatRequest request,
            IntentRouteResult routeResult,
            AgentRuntimeContext runtimeContext,
            SseEmitter emitter,
            String taskSystemMessage,
            Map<String, String> previousOutputs,
            boolean confirmationGranted
    ) {
        // 1. 构建消息历史
        List<ChatMessage> messages = new ArrayList<>();

        // 主系统提示
        messages.add(SystemMessage.from(contextService.getSystemPrompt()));

        // 任务专属系统提示
        if (taskSystemMessage != null && !taskSystemMessage.isBlank()) {
            messages.add(SystemMessage.from(taskSystemMessage));
        }

        // 前置任务输出作为上下文
        if (previousOutputs != null && !previousOutputs.isEmpty()) {
            StringBuilder ctxBuilder = new StringBuilder("前置任务结果：\n");
            for (Map.Entry<String, String> entry : previousOutputs.entrySet()) {
                ctxBuilder.append("- ").append(entry.getKey()).append(": ")
                        .append(entry.getValue().length() > 300
                                ? entry.getValue().substring(0, 300) + "..."
                                : entry.getValue())
                        .append("\n");
            }
            messages.add(SystemMessage.from(ctxBuilder.toString()));
        }

        // 附件上下文
        if (runtimeContext.getAttachmentContext() != null && !runtimeContext.getAttachmentContext().isBlank()) {
            messages.add(SystemMessage.from(runtimeContext.getAttachmentContext()));
        }
        injectStudyContextIfPresent(messages, runtimeContext);

        // 注入强制 ragRetrieve 指令（当前端传递了引用资料范围时）
        if (runtimeContext.isForceRagRetrieve()) {
            messages.add(buildSelectedReferenceInstruction());
            log.info("[SelfManagedReActEngine] 注入强制 ragRetrieve 指令（executeWithTaskContext）");
        }

        // 当前任务作为用户消息
        messages.add(UserMessage.from(request.getMessage()));

        // 2. 替换系统提示词为仅包含当前可用工具的版本
        messages.set(0, buildReActSystemPrompt(routeResult.getRequiredTools()));

        // 3. 构建工具规范
        List<ToolSpecification> tools = toolAdapter.buildToolSpecifications(routeResult.getRequiredTools());

        return runReActLoop(request, routeResult, runtimeContext, emitter, messages, tools,
                confirmationGranted, USER_VISIBLE_OPTIONS);
    }

    /**
     * 带任务上下文的内部 ReAct 执行。
     * <p>
     * 用于 Plan-and-Execute 子步骤：保留 think/tool 事件给执行面板，
     * 但不把子步骤最终回答流式输出到用户正文，也不写入对话历史。
     */
    public String executeInternalTask(
            AgentChatRequest request,
            IntentRouteResult routeResult,
            AgentRuntimeContext runtimeContext,
            SseEmitter emitter,
            String taskSystemMessage,
            Map<String, String> previousOutputs,
            boolean confirmationGranted
    ) {
        List<ChatMessage> messages = buildTaskMessages(request, runtimeContext, taskSystemMessage, previousOutputs);

        // 替换系统提示词为仅包含当前可用工具的版本
        messages.set(0, buildReActSystemPrompt(routeResult.getRequiredTools()));

        List<ToolSpecification> tools = toolAdapter.buildToolSpecifications(routeResult.getRequiredTools());

        return runReActLoop(request, routeResult, runtimeContext, emitter, messages, tools,
                confirmationGranted, INTERNAL_TASK_OPTIONS);
    }

    private List<ChatMessage> buildTaskMessages(
            AgentChatRequest request,
            AgentRuntimeContext runtimeContext,
            String taskSystemMessage,
            Map<String, String> previousOutputs
    ) {
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(SystemMessage.from(contextService.getSystemPrompt()));

        if (taskSystemMessage != null && !taskSystemMessage.isBlank()) {
            messages.add(SystemMessage.from(taskSystemMessage));
        }

        if (previousOutputs != null && !previousOutputs.isEmpty()) {
            StringBuilder ctxBuilder = new StringBuilder("前置任务结果：\n");
            for (Map.Entry<String, String> entry : previousOutputs.entrySet()) {
                ctxBuilder.append("- ").append(entry.getKey()).append(": ")
                        .append(entry.getValue().length() > 300
                                ? entry.getValue().substring(0, 300) + "..."
                                : entry.getValue())
                        .append("\n");
            }
            messages.add(SystemMessage.from(ctxBuilder.toString()));
        }

        if (runtimeContext.getAttachmentContext() != null && !runtimeContext.getAttachmentContext().isBlank()) {
            messages.add(SystemMessage.from(runtimeContext.getAttachmentContext()));
        }
        injectStudyContextIfPresent(messages, runtimeContext);

        // 注入强制 ragRetrieve 指令（当前端传递了引用资料范围时）
        if (runtimeContext.isForceRagRetrieve()) {
            messages.add(buildSelectedReferenceInstruction());
            log.info("[SelfManagedReActEngine] 注入强制 ragRetrieve 指令（buildTaskMessages）");
        }

        messages.add(UserMessage.from(request.getMessage()));
        return messages;
    }

    /**
     * ReAct 循环核心逻辑
     *
     * @return 最终回答文本
     */
    private String runReActLoop(
            AgentChatRequest request,
            IntentRouteResult routeResult,
            AgentRuntimeContext runtimeContext,
            SseEmitter emitter,
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            boolean confirmationGranted,
            ReActRunOptions options
    ) {
        // 4. ReAct 循环 — 每轮非流式 LLM 返回的 AiMessage.thinking() 即为真实思考过程
        String finalAnswer = null;
        StringBuilder allThinkingBuilder = new StringBuilder();

        // 追踪思考分段（与工具调用交错），用于前端还原实时展示效果
        List<Map<String, Object>> reasoningSegments = new ArrayList<>();
        StringBuilder currentThinkingBuilder = new StringBuilder();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            log.info("[ReAct] 第{}轮开始, traceId={}, tools={}",
                    round, runtimeContext.getTraceId(), routeResult.getRequiredTools());

            // 预算检查：超出则抛出 BudgetExceededException，由 AgentRunSupervisor 捕获
            if (runtimeContext.getBudget() != null && runtimeContext.getBudget().isExceeded()) {
                throw new BudgetExceededException("ReAct 执行超出预算: traceId=" + runtimeContext.getTraceId());
            }

            // 取消检查：生成任务模式下断连不再中止执行，只有显式取消才提前退出
            if (runtimeContext.shouldStop()) {
                log.warn("[ReAct] 已取消，提前退出: traceId={}", runtimeContext.getTraceId());
                return null;
            }

            // 记录 LLM 调用
            if (runtimeContext.getBudget() != null) {
                runtimeContext.getBudget().recordLlmCall();
            }

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
                return null;
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

                    // askUser 工具拦截：LLM 需要向用户澄清，暂停 ReAct 循环
                    if ("askUser".equals(toolRequest.name())) {
                        Map<String, Object> clarificationPayload = parseAskUserArguments(
                                toolRequest.arguments());
                        runtimeContext.setClarificationPayload(clarificationPayload);

                        // 发射 tool.started 事件（trace 面板可展示）
                        dispatcher.emit(emitter, runtimeContext, AgentEventType.TOOL_STARTED, Map.of(
                                "toolName", "askUser",
                                "arguments", toolRequest.arguments()
                        ));

                        // 将剩余思考文本作为最后一个分段
                        if (currentThinkingBuilder.length() > 0) {
                            Map<String, Object> finalSeg = new HashMap<>();
                            finalSeg.put("thinkingText", currentThinkingBuilder.toString());
                            reasoningSegments.add(finalSeg);
                            currentThinkingBuilder.setLength(0);
                        }

                        // 保存澄清问题作为 assistant 消息，防止 supervisor 生成兜底回复
                        if (options.saveAssistantMessage()) {
                            String clarificationText = (String) clarificationPayload.get("question");
                            String reasoningContent = allThinkingBuilder.length() > 0
                                    ? allThinkingBuilder.toString() : null;
                            String segmentsJson = reasoningSegments.isEmpty() ? null
                                    : JSON.toJSONString(reasoningSegments);
                            contextService.saveAssistantMessage(
                                    runtimeContext.getConversationId(), request.getUserId(),
                                    clarificationText, reasoningContent,
                                    runtimeContext.getTraceId(), segmentsJson);
                            runtimeContext.setAssistantMessageSaved(true);
                        }

                        log.info("[ReAct] askUser 工具被调用，暂停循环: slotKey={}, answerType={}",
                                clarificationPayload.get("slotKey"),
                                clarificationPayload.get("answerType"));
                        return null;
                    }

                    ToolInvokeContext toolContext = ToolInvokeContext.builder()
                            .userId(request.getUserId())
                            .conversationId(runtimeContext.getConversationId())
                            .traceId(runtimeContext.getTraceId())
                            .mode(runtimeContext.getMode())
                            .runtimeContext(runtimeContext)
                            .currentUserMessage(request.getMessage())
                            .emitter(emitter)
                            .agentName("MainReActAgent")
                            .confirmationGranted(confirmationGranted)
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

                        if (options.saveToolMessages()) {
                            contextService.saveToolMessage(
                                    runtimeContext.getConversationId(), request.getUserId(),
                                    toolRequest.name(), toolResult);
                        }

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

                        // 使用用户可读的通用描述，避免将原始异常信息泄露到 LLM 上下文
                        String userFriendlyMsg = getToolFriendlyError(toolRequest.name());
                        messages.add(ToolExecutionResultMessage.from(toolRequest, userFriendlyMsg));
                    }

                    reasoningSegments.add(seg);
                }

                continue;
            }

            // 6. 无工具调用 → 使用流式模型逐 token 输出最终回答
            String[] result = streamFinalAnswer(messages, tools, emitter, runtimeContext,
                    allThinkingBuilder, currentThinkingBuilder, reasoningSegments, options.streamTextToUser());
            finalAnswer = result[0];

            log.info("[ReAct] 完成, traceId={}, rounds={}", runtimeContext.getTraceId(), round);
            break;
        }

        // 7. 达到最大轮数仍未产出回答
        if (finalAnswer == null) {
            finalAnswer = promptLoader.getMaxRoundsFallback();
            log.warn("[ReAct] 达到最大工具调用轮数, traceId={}", runtimeContext.getTraceId());
            if (options.streamTextToUser()) {
                dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                        "text", finalAnswer
                ));
            }
        }

        if (options.saveAssistantMessage()) {
            String reasoningContent = allThinkingBuilder.length() > 0 ? allThinkingBuilder.toString() : null;
            String segmentsJson = reasoningSegments.isEmpty() ? null : JSON.toJSONString(reasoningSegments);
            contextService.saveAssistantMessage(
                    runtimeContext.getConversationId(), request.getUserId(), finalAnswer, reasoningContent,
                    runtimeContext.getTraceId(), segmentsJson);
            runtimeContext.setAssistantMessageSaved(true);

            summaryService.summarizeIfNeeded(runtimeContext.getConversationId(), request.getUserId());
        }

        return finalAnswer;
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
            List<Map<String, Object>> reasoningSegments,
            boolean streamTextToUser
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
                    if (streamTextToUser) {
                        dispatcher.emit(emitter, runtimeContext, AgentEventType.TEXT_DELTA, Map.of(
                                "text", partialResponse
                        ));
                    }
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

    private record ReActRunOptions(
            boolean streamTextToUser,
            boolean saveAssistantMessage,
            boolean saveToolMessages
    ) {
    }

    /**
     * 解析 askUser 工具调用的参数，转为结构化 payload。
     * LLM 传入的 arguments 是 JSON: {"question":"...","answerType":"SINGLE_SELECT","options":[{"label":"...","value":"..."}],"slotKey":"..."}
     */
    private Map<String, Object> parseAskUserArguments(String argumentsJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = JSON.parseObject(argumentsJson, Map.class);
            payload.put("question", args.getOrDefault("question", "请提供更多信息"));
            payload.put("answerType", args.getOrDefault("answerType", "TEXT"));
            payload.put("slotKey", args.getOrDefault("slotKey", "general"));
            // options 是 List<Map> 格式，直接透传
            Object options = args.get("options");
            payload.put("options", options != null ? options : List.of());
        } catch (Exception e) {
            log.warn("[ReAct] askUser 参数解析失败，使用兜底: {}", e.getMessage());
            payload.put("question", "请提供更多信息以帮助我更好地理解你的需求");
            payload.put("answerType", "TEXT");
            payload.put("slotKey", "general");
            payload.put("options", List.of());
        }
        return payload;
    }

    /**
     * 根据工具名获取用户可读的失败描述。
     * 此路径仅在 toolAdapter.execute() 本身抛出未预期异常时触发
     * （正常失败由 TracingToolExecutor 返回 ToolResult，不走此 catch）。
     */
    private String getToolFriendlyError(String toolName) {
        try {
            return toolAdapter.getToolRegistry().get(toolName).userFriendlyErrorMessage();
        } catch (Exception e) {
            return "暂时无法完成此操作，请尝试其他方法或稍后重试";
        }
    }
}
