package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.domain.entity.ConversationMessage;
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
}
