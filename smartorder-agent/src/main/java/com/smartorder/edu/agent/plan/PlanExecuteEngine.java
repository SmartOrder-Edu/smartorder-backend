package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.dto.AgentChatRequest;
import com.smartorder.edu.agent.route.IntentRouteResult;
import com.smartorder.edu.agent.tool.LangChain4jToolAdapter;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolRegistry;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatLanguageModel;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecuteEngine {

    private final ChatLanguageModel chatLanguageModel;
    private final ToolRegistry toolRegistry;
    private final LangChain4jToolAdapter toolAdapter;
    private final PlanSchemaValidator schemaValidator;
    private final PlanDraftService draftService;
    private final TraceEventDispatcher dispatcher;

    /**
     * 规划阶段：生成规划草稿，等待用户确认
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
        dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                "message", "正在了解你的学习情况..."
        ));

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

        // 3. 调用 PlanAgent 生成规划
        dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                "message", "正在为你量身定制学习规划..."
        ));

        String planJson;
        try {
            PlanAgent planAgent = AiServices.builder(PlanAgent.class)
                    .chatLanguageModel(chatLanguageModel)
                    .build();

            String prompt = buildPlanPrompt(request.getMessage(), profileJson);
            planJson = planAgent.createPlan(prompt);
        } catch (Exception e) {
            log.error("[PlanEngine] PlanAgent 生成失败", e);
            dispatcher.emit(emitter, context, AgentEventType.ERROR, Map.of(
                    "message", "规划生成失败: " + e.getMessage()
            ));
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", "抱歉，生成学习规划时遇到了问题，请稍后再试。"
            ));
            return;
        }

        // 4. Schema 校验
        JSONObject plan;
        try {
            plan = schemaValidator.validateAndParse(planJson);
        } catch (Exception e) {
            log.error("[PlanEngine] Schema 校验失败: {}", e.getMessage());
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", "生成的规划格式不正确，请重新描述你的学习目标。"
            ));
            return;
        }

        // 5. 保存草稿
        String draftId = draftService.saveDraft(
                context.getConversationId(),
                context.getTraceId(),
                request.getUserId(),
                plan
        );

        // 6. 推送 plan.created 事件
        dispatcher.emit(emitter, context, AgentEventType.PLAN_CREATED, Map.of(
                "draftId", draftId,
                "plan", plan
        ));

        // 7. 文本提示
        dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                "text", "我已为你生成了「" + plan.getString("spaceName") + "」的学习规划。" +
                        "包含 " + plan.getJSONArray("phases").size() + " 个学习阶段。" +
                        "请查看规划详情并确认是否创建此学习空间。"
        ));
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
