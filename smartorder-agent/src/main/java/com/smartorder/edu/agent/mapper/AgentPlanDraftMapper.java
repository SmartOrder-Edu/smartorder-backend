package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.domain.entity.AgentPlanDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentPlanDraftMapper extends BaseMapper<AgentPlanDraft> {

    @Select("SELECT * FROM agent_plan_draft WHERE draft_id = #{draftId}")
    AgentPlanDraft selectByDraftId(@Param("draftId") String draftId);
}
