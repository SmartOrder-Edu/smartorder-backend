package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import com.smartorder.edu.agent.model.vo.AgentGenerationVO;
import com.smartorder.edu.agent.runtime.AgentEventSink;
import com.smartorder.edu.agent.runtime.AgentGenerationSubscriptionPlan;

import java.util.Map;

/**
 * 生成任务应用服务——发送 / 订阅 / 查询 / 取消。
 *
 * <p><b>为什么发送与订阅必须拆开</b>：</p>
 * <ol>
 *   <li>发送接口在返回前同步完成事务 A（会话、用户消息、assistant 占位行），
 *       因此可以把 conversationId / messageId 直接返回；即使 SSE 一次都没连上，
 *       前端也知道这段对话落在哪、消息是哪一条。</li>
 *   <li>生成在返回后异步执行，与连接彻底无关；SSE 订阅是只读的，断线重连只是重新订阅。</li>
 *   <li>发送可以安全重试：同一 clientRequestId 命中幂等直接复用，不会重复开轮或重复生成。</li>
 * </ol>
 */
public interface AgentGenerationService {

    AgentGenerationVO create(AgentChatRequest request, Long userId, String clientRequestId);

    AgentGenerationSubscriptionPlan validateSubscription(String generationId, Long userId, long afterSeq, AgentEventSink sink);

    void attachAsync(AgentGenerationSubscriptionPlan plan);

    AgentGenerationVO status(String generationId, Long userId);

    AgentGenerationVO activeGeneration(String conversationId, Long userId);

    Map<String, Object> cancel(String generationId, Long userId);
}
