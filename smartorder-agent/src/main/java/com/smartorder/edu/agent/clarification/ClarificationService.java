package com.smartorder.edu.agent.clarification;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.mapper.AgentClarificationMapper;
import com.smartorder.edu.agent.model.dto.ClarificationPayload;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.entity.AgentClarification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 澄清服务——管理 Clarification Gate 的生命周期。
 * <p>
 * 核心原则：一次只问一个问题，每个问题绑定一个 slot，答案结构化进入 plan context。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarificationService {

    private final AgentClarificationMapper clarificationMapper;

    /**
     * 从 ExecutionStep 的 clarificationSlots 创建多条 AgentClarification 记录。
     * 返回第一条（最高优先级）的 ClarificationPayload。
     *
     * @param step           含 clarificationSlots 的 CLARIFICATION 门步骤
     * @param draftId        关联的规划草稿 ID
     * @param conversationId 会话 ID
     * @param traceId        追踪 ID
     * @param userId         用户 ID
     * @return 第一条待回答的 ClarificationPayload，若没有 slot 则返回 null
     */
    public ClarificationPayload createClarifications(ExecutionStep step, String draftId,
                                                     String conversationId, String traceId, Long userId) {
        List<ExecutionStep.ClarificationSlot> slots = step.getClarificationSlots();
        if (slots == null || slots.isEmpty()) {
            // 兜底：无 clarificationSlots 时从 description 生成单条 TEXT 类型
            slots = List.of(ExecutionStep.ClarificationSlot.builder()
                    .slotKey("general")
                    .question(step.getDescription())
                    .answerType("TEXT")
                    .options(null)
                    .slotPriority(1)
                    .build());
        }

        List<AgentClarification> entities = new ArrayList<>();
        for (ExecutionStep.ClarificationSlot slot : slots) {
            AgentClarification entity = new AgentClarification();
            entity.setClarificationId(UUID.randomUUID().toString());
            entity.setConversationId(conversationId);
            entity.setTraceId(traceId);
            entity.setUserId(userId);
            entity.setDraftId(draftId);
            entity.setTaskId(step.getStepId());
            entity.setSlotKey(slot.getSlotKey());
            entity.setQuestion(slot.getQuestion());
            entity.setAnswerType(slot.getAnswerType() != null ? slot.getAnswerType() : "TEXT");
            entity.setOptionsJson(slot.getOptions() != null ? JSON.toJSONString(slot.getOptions()) : null);
            entity.setStatus("PENDING");
            entity.setSlotPriority(slot.getSlotPriority());
            entities.add(entity);
        }

        // 批量插入
        for (AgentClarification entity : entities) {
            clarificationMapper.insert(entity);
        }

        log.info("[Clarification] 创建 {} 条澄清记录: draftId={}, stepId={}", entities.size(), draftId, step.getStepId());

        // 返回第一条（按 slotPriority 排序）
        return toPayload(entities.get(0));
    }

    /**
     * 获取下一条 PENDING 的澄清记录
     *
     * @return ClarificationPayload 或 null（全部已回答）
     */
    public ClarificationPayload getNextPending(String draftId) {
        AgentClarification next = clarificationMapper.selectPendingByDraftId(draftId);
        return next != null ? toPayload(next) : null;
    }

    /**
     * 提交用户答案
     *
     * @param clarificationId 澄清记录 ID
     * @param answer          用户回答
     * @return 下一条 PENDING 的 ClarificationPayload，若全部已回答则返回 null
     */
    public ClarificationPayload submitAnswer(String clarificationId, String answer) {
        AgentClarification record = clarificationMapper.selectByClarificationId(clarificationId);
        if (record == null) {
            log.warn("[Clarification] 提交答案失败: clarificationId={} 不存在", clarificationId);
            return null;
        }
        if (!"PENDING".equals(record.getStatus())) {
            log.warn("[Clarification] 提交答案失败: clarificationId={} 状态非 PENDING, 当前={}", clarificationId, record.getStatus());
            return null;
        }

        record.setAnswer(answer);
        record.setStatus("ANSWERED");
        record.setAnswerTime(LocalDateTime.now());
        clarificationMapper.updateById(record);

        log.info("[Clarification] 用户回答已提交: clarificationId={}, slotKey={}, answer={}", clarificationId, record.getSlotKey(), answer);

        return getNextPending(record.getDraftId());
    }

    /**
     * 确认用户回答（Agent 回复确认语后调用）
     *
     * @param clarificationId 澄清记录 ID
     * @param ackMessage      Agent 确认语
     */
    public void acknowledge(String clarificationId, String ackMessage) {
        AgentClarification record = clarificationMapper.selectByClarificationId(clarificationId);
        if (record == null) {
            return;
        }
        record.setStatus("ACKNOWLEDGED");
        record.setAckMessage(ackMessage);
        record.setAckTime(LocalDateTime.now());
        clarificationMapper.updateById(record);
    }

    /**
     * 获取草稿的全部澄清记录（历史回放用）
     */
    public List<AgentClarification> getClarificationsForDraft(String draftId) {
        return clarificationMapper.selectByDraftId(draftId);
    }

    /**
     * 获取会话的全部已回答澄清记录（历史回放用）
     */
    public List<AgentClarification> getAnsweredClarifications(String conversationId) {
        return clarificationMapper.selectAnsweredByConversationId(conversationId);
    }

    /**
     * 获取会话的全部澄清记录（历史回放用）
     */
    public List<AgentClarification> getClarificationsForConversation(String conversationId) {
        return clarificationMapper.selectByConversationId(conversationId);
    }

    /**
     * 获取当前活跃的 clarificationId（PAUSED_CLARIFICATION 时使用）
     */
    public String getActiveClarificationId(String draftId) {
        AgentClarification pending = clarificationMapper.selectPendingByDraftId(draftId);
        return pending != null ? pending.getClarificationId() : null;
    }

    /**
     * 将 AgentClarification 实体转为 ClarificationPayload
     */
    private ClarificationPayload toPayload(AgentClarification entity) {
        List<ClarificationPayload.SelectOption> options = null;
        if (entity.getOptionsJson() != null) {
            options = JSON.parseArray(entity.getOptionsJson(), ClarificationPayload.SelectOption.class);
        }
        return ClarificationPayload.builder()
                .clarificationId(entity.getClarificationId())
                .slotKey(entity.getSlotKey())
                .question(entity.getQuestion())
                .answerType(entity.getAnswerType())
                .options(options)
                .slotPriority(entity.getSlotPriority() != null ? entity.getSlotPriority() : 0)
                .build();
    }
}
