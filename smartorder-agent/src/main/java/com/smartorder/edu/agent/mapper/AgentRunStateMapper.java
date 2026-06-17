package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentRunState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunStateMapper extends BaseMapper<AgentRunState> {

    @Select("SELECT * FROM agent_run_state WHERE run_id = #{runId}")
    AgentRunState selectByRunId(@Param("runId") String runId);

    /**
     * 查询指定会话的活跃（非终态）运行状态，最新一条
     */
    @Select("SELECT * FROM agent_run_state WHERE conversation_id = #{conversationId} "
            + "AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') "
            + "ORDER BY update_time DESC LIMIT 1")
    AgentRunState selectActiveByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM agent_run_state WHERE draft_id = #{draftId} ORDER BY update_time DESC LIMIT 1")
    AgentRunState selectByDraftId(@Param("draftId") String draftId);

    /**
     * 行锁版本：在 @Transactional 方法内锁定行，防止并发状态转换
     */
    @Select("SELECT * FROM agent_run_state WHERE draft_id = #{draftId} ORDER BY update_time DESC LIMIT 1 FOR UPDATE")
    AgentRunState selectByDraftIdForUpdate(@Param("draftId") String draftId);
}
