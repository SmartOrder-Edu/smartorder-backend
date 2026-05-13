package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.domain.entity.AgentPlanDraft;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 规划草稿服务——管理规划草稿的生命周期
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDraftService {

    private final AgentPlanDraftMapper draftMapper;

    /**
     * 保存规划草稿
     */
    public String saveDraft(String conversationId, String traceId, Long userId, JSONObject planJson) {
        String draftId = UUID.randomUUID().toString();

        AgentPlanDraft draft = new AgentPlanDraft();
        draft.setDraftId(draftId);
        draft.setConversationId(conversationId);
        draft.setTraceId(traceId);
        draft.setUserId(userId);
        draft.setPlanJson(planJson.toJSONString());
        draft.setStatus("PENDING");
        draft.setExpireTime(LocalDateTime.now().plusHours(24)); // 24 小时过期

        draftMapper.insert(draft);
        log.info("[PlanDraft] 草稿已保存: draftId={}, spaceName={}", draftId, planJson.getString("spaceName"));
        return draftId;
    }

    /**
     * 确认并获取草稿
     */
    public JSONObject confirmAndGet(String draftId, Long userId) {
        AgentPlanDraft draft = draftMapper.selectByDraftId(draftId);

        if (draft == null) {
            throw new IllegalArgumentException("规划草稿不存在: " + draftId);
        }

        if (!"PENDING".equals(draft.getStatus())) {
            throw new IllegalStateException("规划草稿状态不允许确认: " + draft.getStatus());
        }

        if (!draft.getUserId().equals(userId)) {
            throw new SecurityException("无权操作此规划草稿");
        }

        // 更新状态
        draft.setStatus("CONFIRMED");
        draftMapper.updateById(draft);

        return JSONObject.parseObject(draft.getPlanJson());
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
}
