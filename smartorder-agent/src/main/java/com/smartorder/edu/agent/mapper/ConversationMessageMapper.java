package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.vo.ConversationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND is_deleted = 0 ORDER BY create_time DESC LIMIT #{limit}")
    List<ConversationMessage> selectRecentMessages(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = #{conversationId} AND is_compressed = 0 AND is_deleted = 0")
    int countUncompressed(@Param("conversationId") String conversationId);

    @Update("UPDATE conversation_message SET is_compressed = 1 WHERE conversation_id = #{conversationId} AND is_compressed = 0 AND is_deleted = 0 AND id < #{maxId}")
    void markCompressed(@Param("conversationId") String conversationId, @Param("maxId") Long maxId);

    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND is_compressed = 0 AND is_deleted = 0 ORDER BY create_time ASC")
    List<ConversationMessage> selectUncompressedMessages(@Param("conversationId") String conversationId);

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
            cm.max_time AS lastActiveTime
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

    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND is_deleted = 0 ORDER BY create_time ASC LIMIT #{limit} OFFSET #{offset}")
    List<ConversationMessage> selectMessagesPage(@Param("conversationId") String conversationId, @Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND is_deleted = 0")
    int countByConversationId(@Param("conversationId") String conversationId, @Param("userId") Long userId);

    @Update("UPDATE conversation_message SET is_deleted = 1 WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND is_deleted = 0")
    int softDeleteByConversationId(@Param("conversationId") String conversationId, @Param("userId") Long userId);
}
