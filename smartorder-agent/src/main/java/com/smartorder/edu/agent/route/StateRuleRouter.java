package com.smartorder.edu.agent.route;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import com.smartorder.edu.agent.model.dto.TaskList;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 状态感知路由——基于会话状态做出路由决策
 * <p>
 * 这是分层路由的第一层（最高优先级）。
 * 只有在会话状态明确指示某条路径时才返回结果；
 * 否则返回 null，交由下层（关键词/LLM）处理。
 * <p>
 * 支持两种草稿类型：
 * - TASK_LIST（新 PlanThenReAct 架构）：检测 PAUSED_CLARIFICATION / PAUSED_CONFIRMATION
 * - LEGACY（旧 PlanExecuteEngine 架构）：检测 PENDING + 确认词
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateRuleRouter {

    private final AgentPlanDraftMapper planDraftMapper;

    /**
     * 尝试基于会话状态进行路由
     *
     * @return 路由结果，如果状态不明确则返回 null
     */
    public IntentRouteResult tryRoute(RouteContext ctx) {
        String conversationId = ctx.getConversationId();
        if (conversationId == null) {
            return null;
        }

        // ===== 新架构：检测 TASK_LIST 类型的暂停草稿 =====
        AgentPlanDraft taskListDraft = planDraftMapper.selectActiveTaskListDraft(conversationId);
        if (taskListDraft != null && "TASK_LIST".equals(taskListDraft.getPlanType())) {
            TaskList tl = JSON.parseObject(taskListDraft.getTaskListJson(), TaskList.class);

            // PAUSED_CLARIFICATION：用户提供了补充信息
            if ("PAUSED_CLARIFICATION".equals(tl.getStatus())) {
                log.info("[StateRule] TaskList PAUSED_CLARIFICATION → 恢复规划, draftId={}", taskListDraft.getDraftId());
                return IntentRouteResult.builder()
                        .intentType("规划澄清回复")
                        .intentEnum(IntentType.GOAL_PLANNING)
                        .complexity("复杂")
                        .runMode(AgentRunMode.PLAN_THEN_REACT)
                        .requiredTools(List.of())
                        .confidence(0.95)
                        .source(RoutingSource.STATE)
                        .reason("存在待澄清规划，用户提供了补充信息")
                        .needsPlanning(false) // 不需要重新规划，恢复执行
                        .build();
            }

            // PAUSED_CONFIRMATION + 确认词
            if ("PAUSED_CONFIRMATION".equals(tl.getStatus())
                    && ConfirmationWords.isConfirmationOnly(ctx.getMessage())) {
                log.info("[StateRule] TaskList PAUSED_CONFIRMATION + 确认词 → 确认执行, draftId={}", taskListDraft.getDraftId());
                return IntentRouteResult.builder()
                        .intentType(IntentType.PLAN_CONFIRMATION.getChineseName())
                        .intentEnum(IntentType.PLAN_CONFIRMATION)
                        .complexity("复杂")
                        .runMode(AgentRunMode.PLAN_THEN_REACT)
                        .requiredTools(List.of())
                        .confidence(0.95)
                        .source(RoutingSource.STATE)
                        .reason("存在待确认规划草稿且用户确认")
                        .needsPlanning(false)
                        .build();
            }
        }

        // S2: 存在待确认草稿但消息不是确认词 → 可能是对草稿提问，不干预路由
        // S3: (预留) Agent 上一轮问了 yes/no 问题，用户回复简单 yes/no

        return null;
    }
}
