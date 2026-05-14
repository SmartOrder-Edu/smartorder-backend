package com.smartorder.edu.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.common.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 查询当前用户的最近会话列表（获取与每个联系人的最新一条消息）
     */
    @Select("SELECT t1.* FROM chat_message t1 " +
            "INNER JOIN (" +
            "    SELECT " +
            "        IF(sender_id = #{teacherId}, receiver_id, sender_id) AS chat_user_id, " +
            "        MAX(create_time) AS max_time " +
            "    FROM chat_message " +
            "    WHERE sender_id = #{teacherId} OR receiver_id = #{teacherId} " +
            "    GROUP BY chat_user_id" +
            ") t2 ON (" +
            "    (t1.sender_id = #{teacherId} AND t1.receiver_id = t2.chat_user_id) OR " +
            "    (t1.sender_id = t2.chat_user_id AND t1.receiver_id = #{teacherId})" +
            ") AND t1.create_time = t2.max_time " +
            "ORDER BY t1.create_time DESC")
    List<ChatMessage> selectRecentConversations(@Param("teacherId") Long teacherId);
}