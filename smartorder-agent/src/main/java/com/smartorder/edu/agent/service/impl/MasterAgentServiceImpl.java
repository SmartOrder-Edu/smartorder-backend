package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.config.AgentConfiguration;
import com.smartorder.edu.agent.model.dto.PlanCreationResult;
import com.smartorder.edu.agent.service.MasterAgentService;
import com.smartorder.edu.agent.tool.LearningPlanCreationTool;
import com.smartorder.edu.agent.tool.ProfileTool;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 主 Agent 服务实现（已废弃）
 *
 * @deprecated 请使用 {@link com.smartorder.edu.agent.orchestrator.AgentOrchestrator} 替代。
 *             新架构采用 AgentOrchestrator + SelfManagedReActEngine + ToolRegistry 模式，
 *             不再使用手写 JSON 解析 + 两阶段调用。
 *
 * 架构：两阶段调用
 * - Phase 1（非流式）：检测意图 → 执行工具调用 → 获取中间结果
 * - Phase 2（流式）：将工具结果注入上下文，流式生成最终回复
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterAgentServiceImpl implements MasterAgentService {

    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatModel;
    private final AgentConfiguration agentConfiguration;
    private final ProfileTool profileTool;
    private final LearningPlanCreationTool planCreationTool;

    private static final String SYSTEM_PROMPT = """
        你是「知序智学」的主学习 Agent，名为"知序"。你的职责是帮助大学生制定个性化学习计划。

        ## 核心能力
        1. **了解学生**：询问学生的学习目标、可用时间、学科偏好
        2. **制定计划**：根据学生情况，制定结构化的学习路径
        3. **创建空间**：将计划保存为可执行的"学习空间"

        ## 工具使用规范
        当你决定创建学习计划时，请输出以下 JSON 指令（放在单独的代码块中）：
        ```json
        {
          "action": "create_plan",
          "planDescription": "学生的完整学习需求描述，包含目标、时间、学科等信息"
        }
        ```

        ## 行为准则
        - 在学生信息不足时，先提问了解清楚再创建计划
        - 计划创建成功后，简要总结并鼓励学生开始学习
        - 用热情、鼓励的语气交流
        - 回答尽量简洁，避免长篇大论
        """;

    @Override
    public Flux<String> chat(Long userId, String message, String conversationId) {
        String cid = (conversationId != null && !conversationId.isBlank())
                ? conversationId : UUID.randomUUID().toString();

        log.info("[MasterAgent] userId={}, conversationId={}, message={}", userId, cid, message);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Phase 1: 使用非流式模型处理（含工具调用）
        ChatMemory memory = agentConfiguration.getOrCreateMemory(cid);
        List<ChatMessage> messages = buildMessages(memory, message);

        try {
            ChatResponse response = chatLanguageModel.chat(messages);

            AiMessage aiMessage = response.aiMessage();
            String aiText = aiMessage.text();

            // 检测是否包含 plan creation 指令
            String planJson = extractActionJson(aiText, "create_plan");
            PlanCreationResult planResult = null;

            if (planJson != null) {
                // 发送进度提示
                sink.tryEmitNext("[STATUS]正在分析你的学习需求...\n");

                JSONObject obj = JSON.parseObject(planJson);
                String planDescription = obj.getString("planDescription");
                if (planDescription == null || planDescription.isBlank()) {
                    planDescription = "用户需求：" + message;
                }

                // 先获取学生画像
                String profileJson = profileTool.getStudentProfile(userId);
                String enrichedDesc = planDescription + "\n\n## 学生档案\n" + profileJson;

                // 创建学习计划
                sink.tryEmitNext("[STATUS]正在为你量身定制学习计划...\n");
                String toolResult = planCreationTool.createLearningPlan(userId, enrichedDesc);
                planResult = parsePlanResult(toolResult);

                // 将工具结果注入对话记忆
                memory.add(UserMessage.from(message));
                memory.add(AiMessage.from(
                    "学习计划已创建成功！以下是计划概要：\n" +
                    (planResult != null ? JSON.toJSONString(planResult) : toolResult) +
                    "\n请根据以上结果，向学生简要总结计划内容，鼓励学生开始学习。"
                ));
            } else {
                // 无工具调用，直接将消息加入记忆
                memory.add(UserMessage.from(message));
            }

            // Phase 2: 流式生成最终回复
            if (planResult != null) {
                // planResult 非 null → 已通过记忆注入了计划结果，流式生成总结
                streamResponse(memory, userId, sink, planResult);
            } else {
                // 没有计划创建 → 直接将 AI 回复流式返回
                memory.add(aiMessage);
                streamText(aiText, sink);
            }

        } catch (Exception e) {
            log.error("[MasterAgent] 处理失败", e);
            sink.tryEmitNext("[ERROR]抱歉，处理你的请求时出错了：" + e.getMessage());
            sink.tryEmitComplete();
        }

        return sink.asFlux();
    }

    // ============ Private Methods ============

    private List<ChatMessage> buildMessages(ChatMemory memory, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));

        // 添加历史消息
        for (ChatMessage msg : memory.messages()) {
            messages.add(msg);
        }
        // 添加当前用户消息
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    /**
     * 从 AI 回复中提取指定 action 的 JSON
     */
    private String extractActionJson(String text, String action) {
        if (text == null) return null;
        // 尝试提取 ```json ... ``` 块
        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            jsonStart = text.indexOf('\n', jsonStart) + 1;
            int jsonEnd = text.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                String json = text.substring(jsonStart, jsonEnd).trim();
                if (json.contains("\"action\":\"" + action + "\"")) {
                    return json;
                }
            }
        }
        // 尝试直接匹配整个JSON
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            String json = text.substring(braceStart, braceEnd + 1).trim();
            if (json.contains("\"action\":\"" + action + "\"")) {
                return json;
            }
        }
        return null;
    }

    private PlanCreationResult parsePlanResult(String toolResult) {
        if (toolResult == null || !toolResult.startsWith("[PLAN_CREATED]")) return null;
        try {
            String json = toolResult.substring("[PLAN_CREATED]".length());
            return JSON.parseObject(json, PlanCreationResult.class);
        } catch (Exception e) {
            log.warn("解析 PlanCreationResult 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 流式生成最终回复（通过记忆中的上下文）
     */
    private void streamResponse(ChatMemory memory, Long userId, Sinks.Many<String> sink,
                                 PlanCreationResult planResult) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT + "\n\n当前已有学习计划创建完成，请总结并鼓励学生。"));
        for (ChatMessage msg : memory.messages()) {
            messages.add(msg);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        streamingChatModel.chat(messages,
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        sink.tryEmitNext(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        // 发送计划结果标记（前端用于展示 PlanCard）
                        if (planResult != null) {
                            sink.tryEmitNext("[PLAN_RESULT]" + JSON.toJSONString(planResult));
                        }
                        sink.tryEmitComplete();
                        future.complete(null);
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("[MasterAgent] 流式生成失败", error);
                        sink.tryEmitNext("[ERROR]" + error.getMessage());
                        sink.tryEmitComplete();
                        future.completeExceptionally(error);
                    }
                });

        try {
            future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[MasterAgent] 流式等待超时", e);
            sink.tryEmitComplete();
        }
    }

    /**
     * 将普通文本模拟为流式输出
     */
    private void streamText(String text, Sinks.Many<String> sink) {
        if (text == null || text.isBlank()) {
            sink.tryEmitComplete();
            return;
        }
        // 清理文本中的 create_plan JSON 块
        String cleaned = text.replaceAll("```json\\s*\\{[^`]*\"action\":\"create_plan\"[^`]*\\}\\s*```", "");
        sink.tryEmitNext(cleaned.trim());
        sink.tryEmitComplete();
    }
}
