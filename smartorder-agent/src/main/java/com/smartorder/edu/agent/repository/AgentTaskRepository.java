package com.smartorder.edu.agent.repository;

import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.AgentTaskMeta;

import java.util.List;

/**
 * 对话任务元数据仓储。
 *
 * <p>MySQL {@code agent_chat_task} 是任务的权威来源；Redis Stream 只保存事件片段。
 * 查询状态、刷新恢复、幂等重试、中断收敛都走本仓储。</p>
 */
public interface AgentTaskRepository {

    /** 任务行预留结果：created=false 表示命中幂等键，调用方不应再次插入消息。 */
    record Reservation(AgentTaskMeta meta, boolean created) {
    }

    /**
     * 幂等预留任务行。
     */
    Reservation reserve(AgentTaskMeta candidate);

    AgentTaskMeta find(String taskId);

    AgentTaskMeta findByClientRequest(Long userId, String clientRequestId);

    AgentTaskMeta findLatestByConversation(String conversationId);

    /**
     * 事务 A 完成后回填会话与消息 ID。
     */
    void bindTurn(String taskId, String conversationId, String traceId,
                  Long userMessageId, Long assistantMessageId,
                  Integer assistantSequenceNo, String title);

    void markRunning(String taskId);

    void updateTerminalState(String taskId, AgentTaskStatus status, long lastSeq,
                             String errorMessage, Long assistantMessageId, boolean replayable);

    void markReplayDegraded(String taskId);

    /**
     * 删除还没有绑定消息的占位任务（事务 A 失败或进程在 openReply 前退出）。
     */
    void discardUnbound(String taskId);

    /**
     * 进程在 openReply 之前退出留下的未绑定任务；定时清扫使用。
     */
    List<AgentTaskMeta> findStaleReservations();
}