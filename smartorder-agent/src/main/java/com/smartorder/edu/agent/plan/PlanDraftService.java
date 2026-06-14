package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 规划草稿服务——管理执行规划（ExecutionPlan）和学习计划（LearningPlan）草稿的生命周期。
 * <p>
 * 两种草稿类型：
 * <ul>
 *   <li><b>EXECUTION_PLAN</b>：Agent 执行步骤列表，Plan-and-Execute 引擎遍历执行</li>
 *   <li><b>LEARNING_PLAN</b>：学习计划内容（阶段/路径/知识图谱），用户审阅确认后创建学习空间</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDraftService {

    private final AgentPlanDraftMapper draftMapper;

    // ============ ExecutionPlan 草稿 ============

    /**
     * 保存 ExecutionPlan 草稿（Agent 执行步骤列表）
     */
    public String saveExecutionPlanDraft(String conversationId, String traceId, Long userId, ExecutionPlan plan) {
        String draftId = UUID.randomUUID().toString();
        if (plan.getPlanId() == null) {
            plan.setPlanId(draftId);
        }
        plan.setCreatedAt(System.currentTimeMillis());

        AgentPlanDraft draft = new AgentPlanDraft();
        draft.setDraftId(draftId);
        draft.setConversationId(conversationId);
        draft.setTraceId(traceId);
        draft.setUserId(userId);
        draft.setPlanType("EXECUTION_PLAN");
        draft.setExecutionPlanJson(JSON.toJSONString(plan));
        draft.setStatus("PENDING");
        draft.setResumeStepIndex(plan.getCurrentStepIndex());
        draft.setExpireTime(LocalDateTime.now().plusHours(24));

        draftMapper.insert(draft);
        log.info("[PlanDraft] ExecutionPlan草稿已保存: draftId={}, goal={}, stepCount={}",
                draftId, plan.getUserGoal(), plan.getSteps().size());
        return draftId;
    }

    /**
     * 更新 ExecutionPlan 执行进度
     */
    public void updateExecutionPlan(String draftId, ExecutionPlan plan) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null) {
            log.warn("[PlanDraft] 更新ExecutionPlan失败: draftId={} 不存在", draftId);
            return;
        }
        draft.setExecutionPlanJson(JSON.toJSONString(plan));
        draft.setResumeStepIndex(plan.getCurrentStepIndex());

        // 同步状态到 draft 表
        String planStatus = plan.getStatus();
        if ("COMPLETED".equals(planStatus) || "FAILED".equals(planStatus) || "CANCELLED".equals(planStatus)) {
            draft.setStatus("CONFIRMED");
        } else if ("PAUSED_CLARIFICATION".equals(planStatus) || "PAUSED_CONFIRMATION".equals(planStatus)) {
            draft.setStatus("PENDING"); // 保持 PENDING，允许恢复
        }

        draftMapper.updateById(draft);
        log.debug("[PlanDraft] ExecutionPlan已更新: draftId={}, status={}, stepIndex={}",
                draftId, planStatus, plan.getCurrentStepIndex());
    }

    /**
     * 获取指定会话的活跃 ExecutionPlan 草稿（PENDING / PAUSED_*）
     */
    public AgentPlanDraft getActiveExecutionPlanDraft(String conversationId) {
        return draftMapper.selectActiveExecutionPlanDraft(conversationId);
    }

    // ============ LearningPlan 草稿 ============

    /**
     * 保存学习计划草稿（LEARNING_PLAN 类型，用于用户审阅）
     */
    public String saveLearningPlanDraft(String conversationId, String traceId, Long userId,
                                         JSONObject learningPlanJson) {
        String draftId = UUID.randomUUID().toString();

        AgentPlanDraft draft = new AgentPlanDraft();
        draft.setDraftId(draftId);
        draft.setConversationId(conversationId);
        draft.setTraceId(traceId);
        draft.setUserId(userId);
        draft.setPlanJson(learningPlanJson.toJSONString());
        draft.setPlanType("LEARNING_PLAN");
        draft.setStatus("PENDING");
        draft.setExpireTime(LocalDateTime.now().plusHours(24));

        draftMapper.insert(draft);
        log.info("[PlanDraft] 学习计划草稿已保存: draftId={}, spaceName={}",
                draftId, learningPlanJson.getString("spaceName"));
        return draftId;
    }

    /**
     * 获取 LEARNING_PLAN 类型草稿的 planJson
     */
    public JSONObject getLearningPlanJson(String draftId) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null || draft.getPlanJson() == null) {
            throw new IllegalArgumentException("学习计划草稿不存在: " + draftId);
        }
        return JSONObject.parseObject(draft.getPlanJson());
    }

    // ============ 通用操作 ============

    /**
     * 根据 draftId 获取草稿实体（不修改状态）
     */
    public AgentPlanDraft getByDraftId(String draftId) {
        return draftMapper.selectByDraftId(draftId);
    }

    /**
     * 获取指定会话的待确认草稿
     */
    public AgentPlanDraft getPendingDraftByConversationId(String conversationId) {
        return draftMapper.selectPendingDraftByConversationId(conversationId);
    }

    /**
     * 取消草稿
     */
    public void cancelDraft(String draftId, Long userId) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null || !draft.getUserId().equals(userId)) {
            return;
        }
        draft.setStatus("CANCELLED");
        draftMapper.updateById(draft);
    }

    /**
     * 标记草稿已确认
     */
    public void confirmDraft(String draftId, Long userId) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null || !draft.getUserId().equals(userId)) {
            return;
        }
        draft.setStatus("CONFIRMED");
        draftMapper.updateById(draft);
    }
}
