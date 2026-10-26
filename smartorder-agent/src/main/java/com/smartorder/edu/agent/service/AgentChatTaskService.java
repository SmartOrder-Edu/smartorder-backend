package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;

import java.util.Map;

/** 任务元数据与 Agent 执行生命周期；读取快照和 SSE 订阅绝不调用本服务的 create。 */
public interface AgentChatTaskService {

    Map<String, Object> create(AgentChatRequest request, Long userId, String tenantId);

    Map<String, Object> owned(String taskId, Long userId, String tenantId);

    Map<String, Object> snapshot(String taskId, Long userId, String tenantId);

    Map<String, Object> activeForConversation(String conversationId, Long userId, String tenantId);

    boolean isTerminal(String status);

    void failStaleTasks();
}
