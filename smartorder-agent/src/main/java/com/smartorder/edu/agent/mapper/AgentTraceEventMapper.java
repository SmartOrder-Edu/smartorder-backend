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

    /**
     * 批量读取多个 trace 的事件（只返回展示必需字段）。
     *
     * <p>必须带 user_id：traceId 不是授权凭证，不能跨用户读取。
     * 调用方保证 traceIds 非空且不超过 50 个。</p>
     */
    @Select("""
            <script>
            SELECT trace_id, event_type, sequence_num, payload, create_time
            FROM agent_trace_event
            WHERE user_id = #{userId}
              AND trace_id IN
              <foreach collection="traceIds" item="traceId" open="(" separator="," close=")">
                #{traceId}
              </foreach>
            ORDER BY trace_id, sequence_num ASC
            </script>
            """)
    List<AgentTraceEvent> selectByTraceIds(@Param("userId") Long userId,
                                           @Param("traceIds") List<String> traceIds);

    @Select("SELECT * FROM agent_trace_event WHERE conversation_id = #{conversationId} ORDER BY create_time DESC LIMIT #{limit}")
    List<AgentTraceEvent> selectByConversationId(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Delete("DELETE FROM agent_trace_event WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(@Param("conversationId") String conversationId);
}
