package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummary> {

    @Select("SELECT * FROM conversation_summary WHERE conversation_id = #{conversationId} AND is_deleted = 0")
    ConversationSummary selectByConversationId(@Param("conversationId") String conversationId);
}
