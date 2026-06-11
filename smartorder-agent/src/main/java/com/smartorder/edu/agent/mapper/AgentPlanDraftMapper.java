package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentPlanDraftMapper extends BaseMapper<AgentPlanDraft> {

    @Select("SELECT * FROM agent_plan_draft WHERE draft_id = #{draftId}")
    AgentPlanDraft selectByDraftId(@Param("draftId") String draftId);

    @Select("SELECT * FROM agent_plan_draft WHERE conversation_id = #{conversationId} AND status = 'PENDING' ORDER BY create_time DESC LIMIT 1")
    AgentPlanDraft selectPendingDraftByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM agent_plan_draft WHERE conversation_id = #{conversationId} " +
            "AND plan_type = 'TASK_LIST' AND status IN ('PENDING','PAUSED_CONFIRMATION','PAUSED_CLARIFICATION') " +
            "ORDER BY create_time DESC LIMIT 1")
    AgentPlanDraft selectActiveTaskListDraft(@Param("conversationId") String conversationId);
}
