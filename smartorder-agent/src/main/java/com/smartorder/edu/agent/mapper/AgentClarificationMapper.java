package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentClarification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentClarificationMapper extends BaseMapper<AgentClarification> {

    @Select("SELECT * FROM agent_clarification WHERE clarification_id = #{clarificationId}")
    AgentClarification selectByClarificationId(@Param("clarificationId") String clarificationId);

    @Select("SELECT * FROM agent_clarification WHERE draft_id = #{draftId} AND status = 'PENDING' ORDER BY slot_priority ASC LIMIT 1")
    AgentClarification selectPendingByDraftId(@Param("draftId") String draftId);

    @Select("SELECT * FROM agent_clarification WHERE draft_id = #{draftId} ORDER BY slot_priority ASC")
    List<AgentClarification> selectByDraftId(@Param("draftId") String draftId);

    @Select("SELECT * FROM agent_clarification WHERE conversation_id = #{conversationId} AND status IN ('ANSWERED', 'ACKNOWLEDGED') ORDER BY create_time ASC")
    List<AgentClarification> selectAnsweredByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM agent_clarification WHERE conversation_id = #{conversationId} ORDER BY create_time ASC")
    List<AgentClarification> selectByConversationId(@Param("conversationId") String conversationId);
}
