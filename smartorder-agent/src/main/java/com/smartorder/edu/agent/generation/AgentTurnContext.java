package com.smartorder.edu.agent.generation;

import com.smartorder.edu.agent.service.ConversationTurnService;
import lombok.Builder;
import lombok.Data;

/**
 * 一轮对话在“事务 A（落用户消息 + assistant 占位行）”完成后的固定信息。
 *
 * <p>为什么要有这个对象：发送接口必须在**返回响应之前**就把会话与消息写进库里，
 * 这样才能把 conversationId / userMessageId / assistantMessageId 直接返回给前端；
 * 之后的 SSE 只负责增量输出与断线重放，不再承担“创建会话/占位消息”的职责。</p>
 *
 * <p>它同时是编排器的输入：生成任务启动时直接复用这一轮的 ID 和 traceId，
 * 不会在异步线程里再开一次轮次（否则同一句话会出现两条用户消息）。</p>
 */
@Data
@Builder
public class AgentTurnContext {

    /** 本次生成的追踪 ID，创建生成任务时分配 */
    private String traceId;

    private String conversationId;

    private Long conversationPk;

    private Long userMessageId;

    /** assistant 占位行 ID（status=RUNNING），SSE 里 agent.message.created 会带上 */
    private Long assistantMessageId;

    private int userSequenceNo;

    private int assistantSequenceNo;

    /** 本轮兜底标题（首条消息时为截断的用户消息） */
    private String title;

    /** AUTO / LLM / USER，用于判断后续轮次是否还要补标题 */
    private String titleSource;

    /** 本轮是否新建了会话 */
    private boolean created;

    public static AgentTurnContext from(ConversationTurnService.TurnState turn, String traceId) {
        if (turn == null) {
            return null;
        }
        return AgentTurnContext.builder()
                .traceId(traceId)
                .conversationId(turn.getConversationId())
                .conversationPk(turn.getConversationPk())
                .userMessageId(turn.getUserMessageId())
                .assistantMessageId(turn.getAssistantMessageId())
                .userSequenceNo(turn.getUserSequenceNo())
                .assistantSequenceNo(turn.getAssistantSequenceNo())
                .title(turn.getTitle())
                .titleSource(turn.getTitleSource())
                .created(turn.isCreated())
                .build();
    }
}
