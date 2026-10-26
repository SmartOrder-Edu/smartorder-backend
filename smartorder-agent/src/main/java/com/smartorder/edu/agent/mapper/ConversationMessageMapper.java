package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.vo.ConversationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /**
     * 最近 N 条"可用于上下文"的消息。
     * <p>
     * v2 收紧过滤：只取 COMPLETED + is_visible=1。
     * 否则 RUNNING 占位行 / FAILED 半句话 / 重新生成的旧版本都会被喂给模型。
     */
    @Select("""
        SELECT * FROM conversation_message
        WHERE conversation_id = #{conversationId}
          AND is_deleted = 0
          AND status = 'COMPLETED'
          AND is_visible = 1
        ORDER BY sequence_no DESC
        LIMIT #{limit}
        """)
    List<ConversationMessage> selectRecentMessages(@Param("conversationId") String conversationId,
                                                   @Param("limit") int limit);

    @Select("""
        SELECT COUNT(*) FROM conversation_message
        WHERE conversation_id = #{conversationId}
          AND is_compressed = 0 AND is_deleted = 0
          AND status = 'COMPLETED' AND is_visible = 1
          AND role IN ('user','assistant')
        """)
    int countUncompressed(@Param("conversationId") String conversationId);

    /**
     * 压缩标记改用 sequence_no（id 不再保证单调，避免漏标/误标）。
     */
    @Update("""
        UPDATE conversation_message SET is_compressed = 1
        WHERE conversation_id = #{conversationId}
          AND is_compressed = 0 AND is_deleted = 0
          AND sequence_no <= #{maxSequenceNo}
        """)
    void markCompressed(@Param("conversationId") String conversationId,
                        @Param("maxSequenceNo") int maxSequenceNo);

    @Select("""
        SELECT * FROM conversation_message
        WHERE conversation_id = #{conversationId}
          AND is_compressed = 0 AND is_deleted = 0
          AND status = 'COMPLETED' AND is_visible = 1
          AND role IN ('user','assistant')
        ORDER BY sequence_no ASC
        """)
    List<ConversationMessage> selectUncompressedMessages(@Param("conversationId") String conversationId);

    /**
     * 旧版列表查询（消息表聚合）——仅在会话主表不可用（未执行迁移）时兜底。
     */
    @Select("""
        SELECT
            cm.conversation_id AS conversationId,
            COALESCE(
                cs.title,
                LEFT((SELECT m.content FROM conversation_message m WHERE m.conversation_id = cm.conversation_id AND m.role = 'user' AND m.is_deleted = 0 ORDER BY m.create_time ASC LIMIT 1), 50)
            ) AS title,
            LEFT(
                (SELECT m.content FROM conversation_message m WHERE m.conversation_id = cm.conversation_id AND m.content IS NOT NULL AND m.content != '' AND m.is_deleted = 0 ORDER BY m.create_time DESC LIMIT 1), 100
            ) AS lastMessage,
            cm.msg_count AS messageCount,
            cm.max_time AS lastActiveTime,
            cm.max_time AS lastMessageAt,
            'ACTIVE' AS status,
            0 AS pinned
        FROM (
            SELECT
                conversation_id,
                COUNT(*) AS msg_count,
                MAX(create_time) AS max_time
            FROM conversation_message
            WHERE user_id = #{userId} AND is_deleted = 0 AND role IN ('user', 'assistant')
            GROUP BY conversation_id
        ) cm
        LEFT JOIN conversation_summary cs
            ON cs.conversation_id = cm.conversation_id AND cs.is_deleted = 0
        ORDER BY cm.max_time DESC
        """)
    List<ConversationVO> selectUserConversations(@Param("userId") Long userId);

    /**
     * 分页（OFFSET 兼容旧前端）。只返回可见版本，RUNNING / FAILED 也返回，前端据此展示"生成中/失败"。
     */
    @Select("""
        SELECT * FROM conversation_message
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND is_deleted = 0 AND is_visible = 1
        ORDER BY sequence_no ASC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<ConversationMessage> selectMessagesPage(@Param("conversationId") String conversationId,
                                                 @Param("userId") Long userId,
                                                 @Param("limit") int limit,
                                                 @Param("offset") int offset);

    /**
     * 游标分页：取 beforeSequence 之前（更早）的 limit 条，按序号倒序返回，调用方反转成正序。
     */
    @Select("""
        SELECT * FROM conversation_message
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND is_deleted = 0 AND is_visible = 1
          AND sequence_no < #{beforeSequence}
        ORDER BY sequence_no DESC
        LIMIT #{limit}
        """)
    List<ConversationMessage> selectMessagesBefore(@Param("conversationId") String conversationId,
                                                   @Param("userId") Long userId,
                                                   @Param("beforeSequence") int beforeSequence,
                                                   @Param("limit") int limit);

    @Select("""
        SELECT COUNT(*) FROM conversation_message
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND is_deleted = 0 AND is_visible = 1
        """)
    int countByConversationId(@Param("conversationId") String conversationId, @Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM conversation_message
        WHERE conversation_id = #{conversationId} AND is_deleted = 0
          AND status = 'COMPLETED' AND is_visible = 1
        """)
    int countVisibleCompleted(@Param("conversationId") String conversationId);

    /**
     * 取当前会话最大序号——加锁读（current read）。
     * <p>
     * 必须加锁：事务开头可能已有普通 SELECT 建立了快照，
     * 不加锁会读到旧快照里的 MAX(sequence_no)，导致序号重复、唯一键冲突。
     */
    @Select("""
        SELECT sequence_no FROM conversation_message
        WHERE conversation_id = #{conversationId}
        ORDER BY sequence_no DESC
        LIMIT 1
        FOR UPDATE
        """)
    Integer selectTailSequenceNoForUpdate(@Param("conversationId") String conversationId);

    @Select("""
        SELECT id FROM conversation_message
        WHERE conversation_id = #{conversationId} AND role = 'user' AND is_deleted = 0
        ORDER BY sequence_no DESC
        LIMIT 1
        FOR UPDATE
        """)
    Long selectLastUserMessageIdForUpdate(@Param("conversationId") String conversationId);

    @Select("""
        SELECT * FROM conversation_message
        WHERE parent_message_id = #{parentMessageId} AND is_visible = 1 AND is_deleted = 0
        ORDER BY attempt_no DESC
        """)
    List<ConversationMessage> selectVisibleByParent(@Param("parentMessageId") Long parentMessageId);

    /**
     * 收尾占位行：只有仍是 RUNNING 的行才会被更新，返回 0 表示已被收尾过。
     */
    @Update("""
        <script>
        UPDATE conversation_message
        SET status = 'COMPLETED',
            content = #{content},
            <if test="reasoningContent != null">reasoning_content = #{reasoningContent},</if>
            <if test="metadataJson != null">metadata_json = #{metadataJson},</if>
            completed_at = #{completedAt},
            latency_ms = TIMESTAMPDIFF(MICROSECOND, COALESCE(started_at, #{completedAt}), #{completedAt}) / 1000
        WHERE id = #{id} AND status = 'RUNNING'
        </script>
        """)
    int markCompleted(@Param("id") Long id,
                      @Param("content") String content,
                      @Param("reasoningContent") String reasoningContent,
                      @Param("metadataJson") String metadataJson,
                      @Param("completedAt") LocalDateTime completedAt);

    /**
     * 失败收尾：保留已生成的部分内容（可能为空），写入失败原因。
     */
    @Update("""
        <script>
        UPDATE conversation_message
        SET status = 'FAILED',
            content = COALESCE(NULLIF(#{content}, ''), content),
            error_message = #{errorMessage},
            completed_at = #{completedAt},
            latency_ms = TIMESTAMPDIFF(MICROSECOND, COALESCE(started_at, #{completedAt}), #{completedAt}) / 1000
        WHERE id = #{id} AND status = 'RUNNING'
        </script>
        """)
    int markFailed(@Param("id") Long id,
                   @Param("content") String content,
                   @Param("errorMessage") String errorMessage,
                   @Param("completedAt") LocalDateTime completedAt);

    @Update("""
        <script>
        UPDATE conversation_message
        SET status = 'CANCELLED',
            content = COALESCE(NULLIF(#{content}, ''), content),
            completed_at = #{completedAt},
            latency_ms = TIMESTAMPDIFF(MICROSECOND, COALESCE(started_at, #{completedAt}), #{completedAt}) / 1000
        WHERE id = #{id} AND status = 'RUNNING'
        </script>
        """)
    int markCancelled(@Param("id") Long id,
                      @Param("content") String content,
                      @Param("completedAt") LocalDateTime completedAt);

    /**
     * 版本切换：把某条消息标记为不可见（重新生成时用，只影响这一条，不影响同轮其它气泡）。
     */
    @Update("UPDATE conversation_message SET is_visible = 0 WHERE id = #{id}")
    int markInvisible(@Param("id") Long id);

    /**
     * 超时清理：RUNNING 且 started_at 早于阈值的占位行 → FAILED。
     */
    @Update("""
        UPDATE conversation_message
        SET status = 'FAILED',
            error_message = '生成中断（服务重启或进程异常退出），请重新生成',
            completed_at = NOW(3)
        WHERE status = 'RUNNING' AND started_at IS NOT NULL AND started_at < #{threshold}
        """)
    int markZombieFailed(@Param("threshold") LocalDateTime threshold);

    @Update("""
        UPDATE conversation_message SET is_deleted = 1
        WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND is_deleted = 0
        """)
    int softDeleteByConversationId(@Param("conversationId") String conversationId, @Param("userId") Long userId);
}
