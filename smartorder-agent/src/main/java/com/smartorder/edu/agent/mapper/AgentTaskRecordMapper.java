package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentTaskRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话任务表 CRUD。
 *
 * <p>这里只做表操作；任务运行时状态、Redis Stream 回放等逻辑在
 * {@code AgentTaskRegistry} / {@code AgentTaskMetaService} 中。</p>
 */
@Mapper
public interface AgentTaskRecordMapper extends BaseMapper<AgentTaskRecord> {

    @Select("SELECT * FROM agent_chat_task WHERE task_id = #{taskId} LIMIT 1")
    AgentTaskRecord selectByTaskId(@Param("taskId") String taskId);

    @Select("""
        SELECT * FROM agent_chat_task
        WHERE user_id = #{userId} AND task_request_id = #{taskRequestId}
        ORDER BY created_at DESC
        LIMIT 1
        """)
    AgentTaskRecord selectByTaskRequest(@Param("userId") Long userId,
                                        @Param("taskRequestId") String taskRequestId);

    @Select("""
        SELECT * FROM agent_chat_task
        WHERE conversation_id = #{conversationId}
        ORDER BY created_at DESC
        LIMIT 1
        """)
    AgentTaskRecord selectLatestByConversation(@Param("conversationId") String conversationId);

    @Select("""
        SELECT * FROM agent_chat_task
        WHERE status IN ('CREATED', 'RUNNING')
          AND assistant_message_id IS NULL
          AND updated_at < #{threshold}
        ORDER BY updated_at ASC
        LIMIT 200
        """)
    List<AgentTaskRecord> selectStaleReservations(@Param("threshold") LocalDateTime threshold);

    /**
     * 事务 A 完成后回填会话与消息 ID。
     */
    @Update("""
        UPDATE agent_chat_task
        SET conversation_id = #{conversationId},
            trace_id = #{traceId},
            user_message_id = #{userMessageId},
            assistant_message_id = #{assistantMessageId},
            assistant_sequence_no = #{assistantSequenceNo},
            title = #{title},
            updated_at = NOW(3)
        WHERE task_id = #{taskId}
        """)
    int bindTurn(@Param("taskId") String taskId,
                 @Param("conversationId") String conversationId,
                 @Param("traceId") String traceId,
                 @Param("userMessageId") Long userMessageId,
                 @Param("assistantMessageId") Long assistantMessageId,
                 @Param("assistantSequenceNo") Integer assistantSequenceNo,
                 @Param("title") String title);

    @Update("""
        UPDATE agent_chat_task
        SET status = 'RUNNING', updated_at = NOW(3)
        WHERE task_id = #{taskId} AND status = 'CREATED'
        """)
    int markRunning(@Param("taskId") String taskId);

    /**
     * 老任务行没有 draft_id 时补写（升级后第一次带 draft 的重试）。
     * 只在 draft_id 为空时写入，绝不覆盖已有绑定。
     */
    @Update("""
        UPDATE agent_chat_task
        SET draft_id = #{draftId}, updated_at = NOW(3)
        WHERE task_id = #{taskId} AND draft_id IS NULL
        """)
    int bindDraftId(@Param("taskId") String taskId, @Param("draftId") String draftId);

    /**
     * 终态回写：状态、最后 seq、错误、assistant 消息 ID 与完成时间一起落库。
     * 只有终态调用，因此 finished_at 可以直接写 NOW(3)。
     */
    @Update("""
        UPDATE agent_chat_task
        SET status = #{status},
            last_seq = GREATEST(COALESCE(last_seq, 0), #{lastSeq}),
            error_message = #{errorMessage},
            assistant_message_id = COALESCE(#{assistantMessageId}, assistant_message_id),
            replayable = LEAST(COALESCE(replayable, 1), #{replayable}),
            updated_at = NOW(3),
            finished_at = NOW(3)
        WHERE task_id = #{taskId}
        """)
    int updateTerminalState(@Param("taskId") String taskId,
                            @Param("status") String status,
                            @Param("lastSeq") long lastSeq,
                            @Param("errorMessage") String errorMessage,
                            @Param("assistantMessageId") Long assistantMessageId,
                            @Param("replayable") boolean replayable);

    @Update("""
        UPDATE agent_chat_task
        SET replayable = 0, updated_at = NOW(3)
        WHERE task_id = #{taskId}
        """)
    int markReplayDegraded(@Param("taskId") String taskId);

    /**
     * 只允许删除“还没有绑定消息”的占位任务：进程在 openReply 之前退出的残留。
     */
    @Delete("""
        DELETE FROM agent_chat_task
        WHERE task_id = #{taskId}
          AND assistant_message_id IS NULL
          AND status IN ('CREATED', 'RUNNING')
        """)
    int deleteUnbound(@Param("taskId") String taskId);
}
