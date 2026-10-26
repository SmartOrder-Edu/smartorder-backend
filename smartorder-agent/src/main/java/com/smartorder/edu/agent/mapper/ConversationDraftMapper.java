package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.ConversationDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationDraftMapper extends BaseMapper<ConversationDraft> {

    /**
     * 加锁读（current read）：draft 绑定必须看到其他事务刚提交的行，
     * 普通 SELECT 在 REPEATABLE READ 下会读到旧快照，导致唯一键冲突后仍判定“无冲突”。
     */
    @Select("SELECT * FROM agent_conversation_draft WHERE draft_id = #{draftId} LIMIT 1 FOR UPDATE")
    ConversationDraft selectByDraftIdForUpdate(@Param("draftId") String draftId);

    @Select("SELECT * FROM agent_conversation_draft WHERE conversation_id = #{conversationId} LIMIT 1 FOR UPDATE")
    ConversationDraft selectByConversationIdForUpdate(@Param("conversationId") String conversationId);
}
