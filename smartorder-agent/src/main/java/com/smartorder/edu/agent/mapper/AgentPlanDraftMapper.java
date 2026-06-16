package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentPlanDraftMapper extends BaseMapper<AgentPlanDraft> {

    @Select("SELECT * FROM agent_plan_draft WHERE draft_id = #{draftId}")
    AgentPlanDraft selectByDraftId(@Param("draftId") String draftId);

    /**
     * 行锁版本：在事务内通过 FOR UPDATE 锁定草稿行，防止并发确认。
     * 必须在 @Transactional 方法内调用。
     */
    @Select("SELECT * FROM agent_plan_draft WHERE draft_id = #{draftId} FOR UPDATE")
    AgentPlanDraft selectByDraftIdForUpdate(@Param("draftId") String draftId);

    @Select("SELECT * FROM agent_plan_draft WHERE conversation_id = #{conversationId} AND status = 'PENDING' ORDER BY create_time DESC LIMIT 1")
    AgentPlanDraft selectPendingDraftByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM agent_plan_draft WHERE conversation_id = #{conversationId} " +
            "AND plan_type = 'EXECUTION_PLAN' AND status IN ('PENDING','PAUSED_CONFIRMATION','PAUSED_CLARIFICATION') " +
            "ORDER BY create_time DESC LIMIT 1")
    AgentPlanDraft selectActiveExecutionPlanDraft(@Param("conversationId") String conversationId);

    /**
     * 原子化标记草稿为已确认并绑定 spaceId。仅当当前 status='PENDING' 时才更新成功。
     * @return 受影响行数；0 表示并发抢先（应触发事务回滚）
     */
    @Update("UPDATE agent_plan_draft SET status = 'CONFIRMED', created_space_id = #{spaceId}, confirmed_at = NOW() " +
            "WHERE draft_id = #{draftId} AND status = 'PENDING'")
    int confirmAndBindSpace(@Param("draftId") String draftId, @Param("spaceId") Long spaceId);
}
