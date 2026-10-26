package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import com.smartorder.edu.agent.model.dto.RouteContext;
import com.smartorder.edu.agent.model.enums.IntentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 槽位校验器——检查执行意图所需的槽位是否充足
 * <p>
 * 意图识别回答"用户想做什么"，槽位校验回答"执行还缺什么"。
 * 槽位不足时降级置信度，触发澄清流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotValidator {

    private final AgentPlanDraftMapper planDraftMapper;

    /**
     * 校验指定意图的槽位完整度
     *
     * @return 缺失槽位列表（空列表表示所有必需槽位已满足）
     */
    public List<String> validate(IntentType intent, RouteContext ctx) {
        return switch (intent) {
            case PLAN_CONFIRMATION -> validatePlanConfirmation(ctx);
            case GOAL_PLANNING -> validateGoalPlanning(ctx);
            case RESOURCE_GENERATION -> validateResourceGen(ctx);
            // 这些意图无强制槽位
            case KNOWLEDGE_QA, PROBLEM_HELP, DIAGRAM_GENERATION, PROGRESS_REVIEW, TASK_MANAGEMENT, UNKNOWN -> List.of();
        };
    }

    /**
     * 确认创建计划必需：存在待确认草稿
     */
    private List<String> validatePlanConfirmation(RouteContext ctx) {
        List<String> missing = new ArrayList<>();

        String cid = ctx.getConversationId();
        if (cid == null || planDraftMapper.selectPendingDraftByConversationId(cid) == null) {
            missing.add("pendingPlanDraft");
        }

        if (ctx.getMessage() == null || ctx.getMessage().isBlank()) {
            missing.add("userMessage");
        }

        if (!missing.isEmpty()) {
            log.info("[SlotCheck] PLAN_CONFIRMATION 槽位不足: {}", missing);
        }
        return missing;
    }

    /**
     * 目标规划必需：有足够内容的目标描述
     */
    private List<String> validateGoalPlanning(RouteContext ctx) {
        List<String> missing = new ArrayList<>();

        String msg = ctx.getMessage();
        if (msg == null || msg.trim().length() < 5) {
            missing.add("goalDescription");
            log.info("[SlotCheck] GOAL_PLANNING 槽位不足: {}", missing);
        }

        return missing;
    }

    /**
     * 资源生成必需：有明确的主题/科目
     */
    private List<String> validateResourceGen(RouteContext ctx) {
        List<String> missing = new ArrayList<>();

        String msg = ctx.getMessage();
        if (msg == null || msg.trim().length() < 5) {
            missing.add("resourceTopic");
            log.info("[SlotCheck] RESOURCE_GENERATION 槽位不足: {}", missing);
        }

        return missing;
    }
}
