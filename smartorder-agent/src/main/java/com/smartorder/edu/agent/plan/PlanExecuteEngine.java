package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.memory.ConversationMemoryService;
import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.route.ConfirmationWords;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolRegistry;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 规划执行引擎——负责规划阶段
 *
 * 流程：
 * 调用学生画像工具 → PlanAgent 生成 JSON → Schema 校验 → 保存 draft → SSE 推送 agent.plan.created
 *
 * 注意：本引擎只生成规划草稿，不自动创建学习空间。
 * 学习空间的创建必须经过用户确认，由 PlanConfirmService 执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecuteEngine {

    private final ChatModel chatLanguageModel;
    private final ToolRegistry toolRegistry;
    private final LangChain4jToolAdapter toolAdapter;
    private final PlanSchemaValidator schemaValidator;
    private final PlanDraftService draftService;
    private final TraceEventDispatcher dispatcher;
    private final ConversationMemoryService memoryService;

    /**
     * 规划阶段：生成规划草稿（不自动创建学习空间）
     */
    public void runPlanning(
            AgentChatRequest request,
            IntentRouteResult routeResult,
            AgentRuntimeContext context,
            SseEmitter emitter
    ) {
        // 1. 推送阶段变更
        dispatcher.emit(emitter, context, AgentEventType.PHASE_CHANGE, Map.of(
                "phase", "PLANNING",
                "message", "正在分析学习目标并生成规划"
        ));

        // 2. 获取学生画像
        String profileJson = "{}";
        if (toolRegistry.contains("getStudentProfile")) {
            try {
                var tool = toolRegistry.get("getStudentProfile");
                ToolInvokeContext toolCtx = ToolInvokeContext.builder()
                        .userId(request.getUserId())
                        .conversationId(context.getConversationId())
                        .traceId(context.getTraceId())
                        .runtimeContext(context)
                        .emitter(emitter)
                        .build();

                ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                        .name("getStudentProfile")
                        .arguments("{}")
                        .build();

                profileJson = toolAdapter.execute(toolReq, toolCtx);
            } catch (Exception e) {
                log.warn("[PlanEngine] 获取学生画像失败，继续生成规划: {}", e.getMessage());
            }
        }

        // 3. 确定用户目标文本
        String userGoal = resolveUserGoal(request);

        // 4. 调用 PlanAgent 生成规划
        String planJson;
        try {
            PlanAgent planAgent = AiServices.builder(PlanAgent.class)
                    .chatModel(chatLanguageModel)
                    .build();

            String prompt = buildPlanPrompt(userGoal, profileJson);
            planJson = planAgent.createPlan(prompt);
        } catch (Exception e) {
            log.error("[PlanEngine] PlanAgent 生成失败", e);
            String errorMsg = "抱歉，生成学习规划时遇到了问题，请稍后再试。";
            dispatcher.emit(emitter, context, AgentEventType.ERROR, Map.of(
                    "message", "规划生成失败: " + e.getMessage()
            ));
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", errorMsg
            ));
            memoryService.saveAssistantMessage(
                    context.getConversationId(), request.getUserId(), errorMsg, null);
            return;
        }

        // 5. Schema 校验
        JSONObject plan;
        try {
            plan = schemaValidator.validateAndParse(planJson);
        } catch (Exception e) {
            log.error("[PlanEngine] Schema 校验失败: {}", e.getMessage());
            String schemaMsg = "生成的规划格式不正确，请重新描述你的学习目标。";
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", schemaMsg
            ));
            memoryService.saveAssistantMessage(
                    context.getConversationId(), request.getUserId(), schemaMsg, null);
            return;
        }

        // 6. 保存草稿（不创建学习空间，等用户确认后再创建）
        String draftId = draftService.saveDraft(
                context.getConversationId(),
                context.getTraceId(),
                request.getUserId(),
                plan
        );

        log.info("[PlanEngine] 规划草稿已保存: userId={}, draftId={}, 等待用户确认", request.getUserId(), draftId);

        // 7. 推送 plan.created 事件
        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED, Map.of(
                "draftId", draftId,
                "plan", plan,
                "needsConfirmation", true
        ));

        // 8. 文本提示（告知用户需要确认）
        String spaceName = plan.getString("spaceName");
        int phaseCount = plan.getJSONArray("phases").size();
        String resultMsg = "我已为你规划了「" + spaceName + "」的学习路径，包含 " + phaseCount + " 个学习阶段。"
                + "请确认是否创建该学习空间？";

        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of("text", resultMsg));

        // 9. 保存助手回复到 DB
        memoryService.saveAssistantMessage(
                context.getConversationId(), request.getUserId(), resultMsg, null);
    }

    /**
     * 确定用户的学习目标文本
     * 如果当前消息是确认类短消息（如"好的"），则从对话历史中提取原始目标；
     * 否则直接使用当前消息。
     */
    private String resolveUserGoal(AgentChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return "用户希望制定学习计划";
        }

        // 判断是否为确认类短消息
        boolean isConfirmation = ConfirmationWords.isConfirmationOnly(message);

        if (isConfirmation && request.getConversationId() != null) {
            String historicalGoal = memoryService.extractRecentGoal(request.getConversationId());
            if (historicalGoal != null) {
                log.info("[PlanEngine] 从对话历史中恢复用户目标: {}", historicalGoal);
                return historicalGoal;
            }
            log.warn("[PlanEngine] 未能从对话历史中提取目标，使用确认消息本身");
        }

        return message;
    }

    private String buildPlanPrompt(String userMessage, String profileJson) {
        return """
                用户目标：
                %s

                学生画像：
                %s

                请根据用户目标和学生画像，生成一个结构化的学习规划。
                """.formatted(userMessage, profileJson);
    }
}
