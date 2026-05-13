package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.domain.entity.AgentResourceLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentResourceLogMapper extends BaseMapper<AgentResourceLog> {

    @Select("SELECT * FROM agent_resource_log WHERE status = 'PENDING' ORDER BY create_time ASC LIMIT #{limit}")
    List<AgentResourceLog> selectPending(@Param("limit") int limit);

    @Select("SELECT * FROM agent_resource_log WHERE space_id = #{spaceId} ORDER BY phase_index ASC")
    List<AgentResourceLog> selectBySpaceId(@Param("spaceId") Long spaceId);
}
