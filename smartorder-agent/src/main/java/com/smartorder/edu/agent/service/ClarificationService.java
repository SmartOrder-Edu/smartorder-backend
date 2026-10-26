package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.ClarificationPayload;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.entity.AgentClarification;

import java.util.List;

/**
 * 澄清服务——管理 Clarification Gate 的生命周期。
 * <p>
 * 核心原则：一次只问一个问题，每个问题绑定一个 slot，答案结构化进入 plan context。
 */
public interface ClarificationService {

    ClarificationPayload createClarifications(ExecutionStep step, String draftId, String conversationId, String traceId, Long userId);

    ClarificationPayload getNextPending(String draftId);

    ClarificationPayload submitAnswer(String clarificationId, String answer);

    ClarificationPayload submitAnswer(String clarificationId, String answer, String expectedDraftId, Long expectedUserId);

    void acknowledge(String clarificationId, String ackMessage);

    List<AgentClarification> getClarificationsForDraft(String draftId);

    List<AgentClarification> getAnsweredClarifications(String conversationId);

    List<AgentClarification> getClarificationsForConversation(String conversationId);

    String getActiveClarificationId(String draftId);
}
