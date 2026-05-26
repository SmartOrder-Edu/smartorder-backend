package com.smartorder.edu.agent.orchestrator;

import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.subagent.*;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 多智能体协调器——复杂资源生成任务拆给多个子智能体并行执行
 *
 * 架构：
 * 任务拆解 → CompletableFuture 并行执行 → 汇总摘要 → 输出最终回复
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentCoordinator {

    private static final int SUB_AGENT_TIMEOUT_SECONDS = 120;

    private final ResourceSubAgent resourceSubAgent;
    private final QuizSubAgent quizSubAgent;
    private final KnowledgeGraphSubAgent knowledgeGraphSubAgent;
    private final TraceEventDispatcher dispatcher;
    private final SubAgentMailbox mailbox;
    private final ConversationMemoryService memoryService;

    private final ExecutorService subAgentExecutor = Executors.newFixedThreadPool(4);

    /**
     * 执行多智能体协作
     */
    public void execute(
            AgentChatRequest request,
            IntentRouteResult routeResult,
            AgentRuntimeContext context,
            SseEmitter emitter
    ) {
        // 1. 推送初始思考
        dispatcher.emit(emitter, context, AgentEventType.THINK_DELTA, Map.of(
                "text", "这是一个复杂资源生成任务，我会拆分给 3 个子智能体并行完成：\n" +
                        "• 资源生成智能体——生成学习资料大纲\n" +
                        "• 测评智能体——生成配套练习题\n" +
                        "• 知识图谱智能体——提取知识点依赖关系"
        ));

        String sessionKey = context.getTraceId();

        // 2. 创建子任务
        List<CompletableFuture<SubAgentResult>> futures = List.of(
                dispatchSubAgent("resource-sub", resourceSubAgent,
                        "为以下学习需求生成资源大纲：" + request.getMessage(),
                        context, emitter, sessionKey),
                dispatchSubAgent("quiz-sub", quizSubAgent,
                        "为以下学习需求生成练习题：" + request.getMessage(),
                        context, emitter, sessionKey),
                dispatchSubAgent("kg-sub", knowledgeGraphSubAgent,
                        "为以下学习需求提取知识图谱：" + request.getMessage(),
                        context, emitter, sessionKey)
        );

        // 3. 等待所有子智能体完成
        List<SubAgentResult> results = futures.stream()
                .map(this::safeJoin)
                .filter(Objects::nonNull)
                .toList();

        // 4. 汇总结果
        String finalAnswer = aggregate(results);

        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                "text", finalAnswer
        ));

        // 5. 保存助手回复到 DB
        memoryService.saveAssistantMessage(
                context.getConversationId(), request.getUserId(), finalAnswer, null,
                context.getTraceId());

        // 6. 清理信箱
        mailbox.clear(sessionKey);

        log.info("[MultiAgent] 完成: traceId={}, subAgentCount={}", context.getTraceId(), results.size());
    }

    // ============ Private Methods ============

    private CompletableFuture<SubAgentResult> dispatchSubAgent(
            String taskId, SubAgent subAgent, String taskDescription,
            AgentRuntimeContext context, SseEmitter emitter, String sessionKey
    ) {
        SubAgentTask task = SubAgentTask.builder()
                .taskId(taskId)
                .agentName(subAgent.name())
                .taskDescription(taskDescription)
                .timeoutSeconds(SUB_AGENT_TIMEOUT_SECONDS)
                .build();

        return CompletableFuture.supplyAsync(() -> {
            // 推送子智能体开始事件
            dispatcher.emit(emitter, context, AgentEventType.SUB_AGENT_STARTED, Map.of(
                    "agentName", subAgent.name(),
                    "taskId", taskId,
                    "description", subAgent.description()
            ));

            try {
                SubAgentResult result = subAgent.run(task, context, emitter);

                // 投递结果到信箱
                mailbox.deliver(sessionKey, result);

                if (result.getSuccess()) {
                    dispatcher.emit(emitter, context, AgentEventType.SUB_AGENT_COMPLETED, Map.of(
                            "agentName", subAgent.name(),
                            "taskId", taskId,
                            "summary", result.getSummary()
                    ));
                } else {
                    dispatcher.emit(emitter, context, AgentEventType.SUB_AGENT_FAILED, Map.of(
                            "agentName", subAgent.name(),
                            "taskId", taskId,
                            "error", result.getErrorMessage()
                    ));
                }

                return result;

            } catch (Exception e) {
                log.error("[MultiAgent] 子智能体异常: {}", subAgent.name(), e);
                dispatcher.emit(emitter, context, AgentEventType.SUB_AGENT_FAILED, Map.of(
                        "agentName", subAgent.name(),
                        "taskId", taskId,
                        "error", e.getMessage()
                ));
                return SubAgentResult.builder()
                        .taskId(taskId)
                        .agentName(subAgent.name())
                        .success(false)
                        .summary(subAgent.name() + "执行异常")
                        .errorMessage(e.getMessage())
                        .build();
            }
        }, subAgentExecutor).orTimeout(SUB_AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("[MultiAgent] 子智能体超时或异常: {}", subAgent.name());
                    return SubAgentResult.builder()
                            .taskId(taskId)
                            .agentName(subAgent.name())
                            .success(false)
                            .summary(subAgent.name() + "执行超时")
                            .errorMessage(e.getMessage())
                            .build();
                });
    }

    private SubAgentResult safeJoin(CompletableFuture<SubAgentResult> future) {
        try {
            return future.get(SUB_AGENT_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[MultiAgent] safeJoin 失败: {}", e.getMessage());
            return null;
        }
    }

    private String aggregate(List<SubAgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 资源生成任务已完成\n\n");

        int successCount = 0;
        int failCount = 0;

        for (SubAgentResult result : results) {
            if (result.getSuccess()) {
                successCount++;
                sb.append("### ").append(result.getAgentName()).append(" ✓\n");
                sb.append(result.getSummary()).append("\n\n");
                if (result.getData() instanceof String data) {
                    // 截断过长内容
                    String display = data.length() > 500 ? data.substring(0, 500) + "..." : data;
                    sb.append("```\n").append(display).append("\n```\n\n");
                }
            } else {
                failCount++;
                sb.append("### ").append(result.getAgentName()).append(" ✗\n");
                sb.append("错误: ").append(result.getErrorMessage()).append("\n\n");
            }
        }

        sb.append("---\n");
        sb.append("成功: ").append(successCount).append(" / 失败: ").append(failCount).append("\n");

        return sb.toString();
    }
}
