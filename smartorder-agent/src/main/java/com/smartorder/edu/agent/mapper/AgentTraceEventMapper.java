package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentTraceEventMapper extends BaseMapper<AgentTraceEvent> {

    @Select("SELECT * FROM agent_trace_event WHERE trace_id = #{traceId} ORDER BY sequence_num ASC")
    List<AgentTraceEvent> selectByTraceId(@Param("traceId") String traceId);

    @Select("SELECT * FROM agent_trace_event WHERE conversation_id = #{conversationId} ORDER BY create_time DESC LIMIT #{limit}")
    List<AgentTraceEvent> selectByConversationId(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Delete("DELETE FROM agent_trace_event WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(@Param("conversationId") String conversationId);
}
