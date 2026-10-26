package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.vo.ConversationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Select("SELECT * FROM conversation WHERE conversation_uuid = #{conversationUuid} LIMIT 1")
    Conversation selectByConversationUuid(@Param("conversationUuid") String conversationUuid);

    /**
     * 行锁读取——同一会话的序号分配与写入必须串行化。
     * 必须在事务内调用，锁持有到事务提交为止。
     */
    @Select("SELECT * FROM conversation WHERE conversation_uuid = #{conversationUuid} LIMIT 1 FOR UPDATE")
    Conversation selectByConversationUuidForUpdate(@Param("conversationUuid") String conversationUuid);

    @Select("""
        SELECT conversation_uuid     AS conversationId,
               conversation_uuid     AS conversationUuid,
               title                 AS title,
               last_message_preview  AS lastMessage,
               message_count         AS messageCount,
               last_message_at       AS lastActiveTime,
               last_message_at       AS lastMessageAt,
               status                AS status,
               pinned                AS pinned,
               created_at            AS createdAt
        FROM conversation
        WHERE user_id = #{userId} AND status = 'ACTIVE'
        ORDER BY pinned DESC, last_message_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<ConversationVO> selectPageByUser(@Param("userId") Long userId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Select("""
        SELECT conversation_uuid     AS conversationId,
               conversation_uuid     AS conversationUuid,
               title                 AS title,
               last_message_preview  AS lastMessage,
               message_count         AS messageCount,
               last_message_at       AS lastActiveTime,
               last_message_at       AS lastMessageAt,
               status                AS status,
               pinned                AS pinned,
               created_at            AS createdAt
        FROM conversation
        WHERE conversation_uuid = #{conversationUuid} AND user_id = #{userId}
        LIMIT 1
        """)
    ConversationVO selectVoByConversationUuid(@Param("conversationUuid") String conversationUuid,
                                              @Param("userId") Long userId);

    @Update("""
        UPDATE conversation
        SET title = #{title}, title_source = 'USER'
        WHERE conversation_uuid = #{conversationUuid} AND user_id = #{userId} AND status = 'ACTIVE'
        """)
    int updateTitle(@Param("conversationUuid") String conversationUuid,
                    @Param("userId") Long userId,
                    @Param("title") String title);

    /**
     * 异步 LLM 标题回写——只在"标题仍是首条消息截断的自动标题"时才覆盖。
     * <p>
     * 条件 {@code title_source = 'AUTO'} 同时承担三件事：
     * 1) 用户已手动改名（USER）→ 不覆盖，尊重用户意图；
     * 2) 已经生成过 LLM 标题（LLM）→ 不重复覆盖，天然幂等；
     * 3) 同一会话并发两条"首条消息"（极端重试场景）→ 只有第一次 UPDATE 命中，
     *    调用方据返回值决定是否推送 SSE，避免重复推送。
     */
    @Update("""
        UPDATE conversation
        SET title = #{title}, title_source = 'LLM'
        WHERE conversation_uuid = #{conversationUuid} AND title_source = 'AUTO' AND status = 'ACTIVE'
        """)
    int updateTitleIfAuto(@Param("conversationUuid") String conversationUuid,
                          @Param("title") String title);

    /**
     * 更新列表排序键与副标题（免回表）
     */
    @Update("""
        UPDATE conversation
        SET last_message_at = #{lastMessageAt}, last_message_preview = #{preview}
        WHERE conversation_uuid = #{conversationUuid}
        """)
    int touchLastMessage(@Param("conversationUuid") String conversationUuid,
                         @Param("lastMessageAt") LocalDateTime lastMessageAt,
                         @Param("preview") String preview);

    @Update("UPDATE conversation SET message_count = GREATEST(message_count + #{delta}, 0) WHERE conversation_uuid = #{conversationUuid}")
    int incrMessageCount(@Param("conversationUuid") String conversationUuid, @Param("delta") int delta);

    @Update("UPDATE conversation SET message_count = #{messageCount} WHERE conversation_uuid = #{conversationUuid}")
    int updateMessageCount(@Param("conversationUuid") String conversationUuid,
                           @Param("messageCount") int messageCount);

    @Update("""
        UPDATE conversation
        SET status = 'DELETED', deleted_at = NOW(3)
        WHERE conversation_uuid = #{conversationUuid} AND user_id = #{userId} AND status <> 'DELETED'
        """)
    int softDelete(@Param("conversationUuid") String conversationUuid,
                   @Param("userId") Long userId);
}
