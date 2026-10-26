package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话任务的元数据（不含事件正文）。
 *
 * <p>权威副本存 MySQL {@code agent_chat_task}，Redis Stream 只保存可回放的事件片段。
 * 这样进程重启、Redis 抖动或任务跨实例迁移时，任务归属、状态和消息关联仍然可查。</p>
 *
 * <p>一条 assistant 消息对应一个任务（1:1）：{@link #assistantMessageId} 是占位行主键，
 * {@code conversation_message.task_id} 与 {@link #taskId} 写入同一个值。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskMeta {

    private String taskId;

    /** 会话 public_id；新会话在 openReply 之后才能确定，因此允许为空 */
    private String conversationId;

    /** 归属用户——订阅/查询/取消都必须校验，不能把 taskId 当授权凭证 */
    private Long userId;

    private String traceId;

    /** 客户端幂等键，同一个用户重复提交时复用同一个任务 */
    private String clientRequestId;

    private String status;

    private long createdAt;

    private long updatedAt;

    /** 已产生事件的最大 seq（终态回写；运行中以 Redis Stream 尾部为准） */
    private long lastSeq;

    /** 用户消息行 ID */
    private Long userMessageId;

    /** assistant 占位行 ID */
    private Long assistantMessageId;

    private Integer assistantSequenceNo;

    /** 会话兜底标题 */
    private String title;

    private String errorMessage;

    private long finishedAt;

    /**
     * 事件是否完整可回放。
     * <p>异步镜像写 Redis 失败时会置 false：此时只能靠本机内存回放，
     * 跨实例/重启后的续传会返回 CURSOR_EXPIRED，而不是静默跳到最新事件。</p>
     */
    @Builder.Default
    private boolean replayable = true;

    public AgentTaskStatus statusEnum() {
        AgentTaskStatus parsed = AgentTaskStatus.fromName(status);
        return parsed != null ? parsed : AgentTaskStatus.RUNNING;
    }
}
