package com.smartorder.edu.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话任务持久化实体。
 *
 * <p>一条 assistant 消息对应一个任务：用户发送消息时先插入任务行，再插入
 * user / assistant 两条 {@code conversation_message}，两者的 {@code task_id}
 * 都等于 {@link #taskId}。任务行是查询、鉴权、刷新恢复和中断恢复的权威来源；
 * Redis Stream 只保存生成过程中的消息片段。</p>
 */
@Data
@NoArgsConstructor
@TableName("agent_chat_task")
public class AgentTaskRecord {

    /** 任务 ID（UUID），同时写入 conversation_message.task_id */
    @TableId(type = IdType.INPUT)
    private String taskId;

    private String conversationId;

    /** 客户端草稿ID：同一 requestId 只允许绑定同一个 draftId */
    private String draftId;

    private Long userId;

    private Long userMessageId;

    private Long assistantMessageId;

    private Integer assistantSequenceNo;

    /** 任务请求幂等键：同一用户重试只生成一个任务 */
    private String taskRequestId;

    /** 本轮追踪 ID */
    private String traceId;

    /** CREATED / RUNNING / COMPLETED / FAILED / CANCELLED / INTERRUPTED */
    private String status;

    /** 终态回写的事件最大 seq；运行中以 Redis Stream 尾部为准 */
    private Long lastSeq;

    private String title;

    private String errorMessage;

    /** 事件是否完整可回放：1 是，0 否 */
    private Boolean replayable;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
