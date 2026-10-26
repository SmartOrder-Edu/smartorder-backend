package com.smartorder.edu.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.PlanContext;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
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
     * 保存/更新 ExecutionPlan 的 PlanContext（结构化澄清上下文）
     */
    public void savePlanContext(String draftId, PlanContext planContext) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null) return;
        draft.setPlanContextJson(planContext != null ? JSON.toJSONString(planContext) : null);
        draftMapper.updateById(draft);
    }

    /**
     * 读取 ExecutionPlan 的 PlanContext
     */
    public PlanContext getPlanContext(String draftId) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null || draft.getPlanContextJson() == null) return null;
        try {
            return JSON.parseObject(draft.getPlanContextJson(), PlanContext.class);
        } catch (Exception e) {
            log.warn("[PlanDraft] PlanContext 反序列化失败: draftId={}", draftId);
            return null;
        }
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

    // ============ 幂等校验（LEARNING_PLAN 草稿确认前置） ============

    /**
     * 校验草稿是否可被当前用户确认。不抛异常，由调用方根据 status 分支处理（SSE 错误事件）。
     *
     * @param draftId 草稿 ID
     * @param currentUserId 当前用户 ID
     * @return 校验结果（含具体错误状态、原 draft 实体、若已确认则带回 createdSpaceId）
     */
    public DraftValidationResult validateForConfirm(String draftId, Long currentUserId) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);
        if (draft == null) {
            return new DraftValidationResult(DraftValidationResult.Status.NOT_FOUND, null, null,
                    "学习计划草稿不存在: " + draftId);
        }
        if (!draft.getUserId().equals(currentUserId)) {
            return new DraftValidationResult(DraftValidationResult.Status.FORBIDDEN, draft, null,
                    "无权操作他人的学习计划草稿");
        }
        if (!"LEARNING_PLAN".equals(draft.getPlanType())) {
            return new DraftValidationResult(DraftValidationResult.Status.WRONG_TYPE, draft, null,
                    "草稿类型不是 LEARNING_PLAN: " + draft.getPlanType());
        }
        // 已确认 → 幂等返回已创建的 spaceId
        if ("CONFIRMED".equals(draft.getStatus())) {
            return new DraftValidationResult(DraftValidationResult.Status.ALREADY_CONFIRMED, draft,
                    draft.getCreatedSpaceId(),
                    "草稿已确认，对应学习空间 ID=" + draft.getCreatedSpaceId());
        }
        if ("CANCELLED".equals(draft.getStatus())) {
            return new DraftValidationResult(DraftValidationResult.Status.WRONG_STATUS, draft, null,
                    "草稿已取消，无法确认");
        }
        if ("EXPIRED".equals(draft.getStatus())) {
            return new DraftValidationResult(DraftValidationResult.Status.EXPIRED, draft, null,
                    "草稿已过期");
        }
        if (!"PENDING".equals(draft.getStatus())) {
            return new DraftValidationResult(DraftValidationResult.Status.WRONG_STATUS, draft, null,
                    "草稿状态非 PENDING: " + draft.getStatus());
        }
        // 过期时间检查
        if (draft.getExpireTime() != null && LocalDateTime.now().isAfter(draft.getExpireTime())) {
            return new DraftValidationResult(DraftValidationResult.Status.EXPIRED, draft, null,
                    "草稿已超过有效期 (expireTime=" + draft.getExpireTime() + ")");
        }
        return new DraftValidationResult(DraftValidationResult.Status.OK, draft, null, "OK");
    }

    /**
     * 校验结果。OK 时 draft 非空；ALREADY_CONFIRMED 时 createdSpaceId 非空。
     */
    public record DraftValidationResult(
            Status status,
            AgentPlanDraft draft,
            Long createdSpaceId,
            String message
    ) {
        public enum Status {
            OK,
            NOT_FOUND,
            FORBIDDEN,
            EXPIRED,
            ALREADY_CONFIRMED,
            WRONG_TYPE,
            WRONG_STATUS
        }
    }
}
