package com.smartorder.edu.agent.model.vo;

import com.smartorder.edu.agent.model.enums.AgentGenerationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生成任务的对外视图。
 *
 * <p>发送接口返回的就是它：前端在**建立 SSE 之前**就能拿到会话与消息 ID，
 * 因此即使事件流立刻失败，页面也知道这段对话属于哪个会话、占位消息是哪一条。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentGenerationVO {

    private String generationId;

    private String conversationId;

    /** 本轮追踪 ID（agent_trace_event 关联用） */
    private String traceId;

    /** 用户消息行 ID（conversation_message.id） */
    private Long userMessageId;

    /** assistant 占位行 ID，status=RUNNING */
    private Long assistantMessageId;

    /** assistant 占位行序号 */
    private Integer assistantSequenceNo;

    /** 会话兜底标题（首条消息时可直接用于侧边栏） */
    private String title;

    /** 见 {@link AgentGenerationStatus} */
    private String status;

    /** 创建接口：true 表示本次新建；false 表示命中幂等键、复用已有生成 */
    private boolean created;

    /** 已产生事件的最大 seq，客户端断线重连时用它作为 after 游标 */
    private long lastSeq;

    private String errorMessage;

    /** 事件是否完整可回放；false 时跨进程续传会返回 CURSOR_EXPIRED */
    private boolean replayable;

    private long createdAt;
}
