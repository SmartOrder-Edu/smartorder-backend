package com.smartorder.edu.agent.route;

import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
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

        // S1: 存在待确认草稿 AND 消息是确认词 → 确认创建计划
        AgentPlanDraft pending = planDraftMapper.selectPendingDraftByConversationId(conversationId);
        if (pending != null && ConfirmationWords.isConfirmationOnly(ctx.getMessage())) {
            log.info("[StateRule] 待确认草稿+确认词 → PLAN_CONFIRMATION, draftId={}", pending.getDraftId());
            return IntentRouteResult.builder()
                    .intentType(IntentType.PLAN_CONFIRMATION.getChineseName())
                    .intentEnum(IntentType.PLAN_CONFIRMATION)
                    .complexity("复杂")
                    .runMode(AgentRunMode.PLAN_EXECUTE)
                    .requiredTools(List.of("getStudentProfile"))
                    .confidence(0.95)
                    .source(RoutingSource.STATE)
                    .reason("存在待确认规划草稿且用户发送了确认消息")
                    .build();
        }

        // S2: 存在待确认草稿但消息不是确认词 → 可能是对草稿提问，不干预路由
        // S3: (预留) Agent 上一轮问了 yes/no 问题，用户回复简单 yes/no

        return null;
    }
}
