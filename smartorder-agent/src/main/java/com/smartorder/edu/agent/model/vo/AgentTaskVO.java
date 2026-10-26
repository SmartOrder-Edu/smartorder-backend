package com.smartorder.edu.agent.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话任务的对外视图。
 *
 * <p>发送接口返回的就是它：前端在**建立 SSE 之前**就能拿到 taskId 以及会话、
 * 用户消息、assistant 占位消息 ID。即使事件流一次都没连上，页面也知道这段对话
 * 属于哪个会话、要查询哪个任务的消息片段。</p>
 *
 * <p><b>1:1 语义</b>：一条 assistant 消息对应一个 task；
 * {@code conversation_message.task_id} 与 {@link #taskId} 使用同一个值。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskVO {

    private String taskId;

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

    /** 见 {@link AgentTaskStatus} */
    private String status;

    /** 创建接口：true 表示本次新建；false 表示命中 requestId 或 (conversationId, messageUuid) 幂等 */
    private boolean created;

    /** 已产生事件的最大 seq，客户端断线重连时用它作为 after 游标 */
    private long lastSeq;

    private String errorMessage;

    /** 事件是否完整可回放；false 时跨进程续传会返回 CURSOR_EXPIRED */
    private boolean replayable;

    private long createdAt;

    /**
     * 兼容旧客户端的只读别名（旧路径 /agent/generations 仍可读取）。
     *
     * @deprecated 新代码只使用 {@link #taskId}
     */
    @Deprecated
    @JsonProperty("generationId")
    public String getGenerationIdAlias() {
        return taskId;
    }
}
