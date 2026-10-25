package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentChatTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 可恢复 Agent 对话任务的持久化入口。
 * <p>
 * 约定：
 * 1) 所有 {@code agent_chat_task} 的读写只经过本 Mapper，Service 不再内嵌 SQL；
 * 2) 涉及事件投影与游标的更新必须保持"内容 + 游标"同一条 SQL，避免快照超前于事件；
 * 3) 行锁方法（{@code ForUpdate} 后缀）必须在事务内调用，锁持有到事务提交。
 */
@Mapper
public interface AgentChatTaskMapper extends BaseMapper<AgentChatTask> {

    /** 按任务所有者与租户读取；跨账号一律查不到，由 Service 转成统一的不可访问错误。 */
    @Select("""
        SELECT * FROM agent_chat_task
        WHERE task_id = #{taskId} AND user_id = #{userId} AND tenant_id = #{tenantId}
        LIMIT 1
        """)
    AgentChatTask selectOwned(@Param("taskId") String taskId,
                              @Param("userId") Long userId,
                              @Param("tenantId") String tenantId);

    /** 幂等键仲裁：唯一索引冲突后回查既有任务。 */
    @Select("""
        SELECT * FROM agent_chat_task
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND request_id = #{requestId}
        LIMIT 1
        """)
    AgentChatTask selectByRequestId(@Param("tenantId") String tenantId,
                                    @Param("userId") Long userId,
                                    @Param("requestId") String requestId);

    /** 会话最近一次任务（含终态），供 F5 恢复判断。 */
    @Select("""
        SELECT * FROM agent_chat_task
        WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND tenant_id = #{tenantId}
        ORDER BY created_at DESC
        LIMIT 1
        """)
    AgentChatTask selectLatestByConversation(@Param("conversationId") String conversationId,
                                             @Param("userId") Long userId,
                                             @Param("tenantId") String tenantId);

    @Update("UPDATE agent_chat_task SET status = 'RUNNING' WHERE task_id = #{taskId}")
    int markRunning(@Param("taskId") String taskId);

    /** 终态写入：先落库再发事件，快照可兜底恢复终态。 */
    @Update("UPDATE agent_chat_task SET status = #{status}, finished_at = NOW(3) WHERE task_id = #{taskId}")
    int markFinished(@Param("taskId") String taskId, @Param("status") String status);

    /** 调度失败（线程池拒绝等）：直接收敛为失败，避免客户端等待永不执行的任务。 */
    @Update("UPDATE agent_chat_task SET status = 'FAILED', finished_at = NOW(3) WHERE task_id = #{taskId}")
    int markDispatchFailed(@Param("taskId") String taskId);

    /**
     * 首次写入事件前独立提交标记。
     * <p>
     * 返回 1 表示本次为世代内的第一条事件；返回 0 表示已标记过。
     * 该标记先于 XADD 提交，即使首条事件 SQL 失败也不会悄悄创建新 Stream。
     */
    @Update("UPDATE agent_chat_task SET stream_started = 1 WHERE task_id = #{taskId} AND stream_started = 0")
    int markStreamStarted(@Param("taskId") String taskId);

    /** 行锁读取事件数：事件写入与投影在跨实例场景下必须串行。 */
    @Select("SELECT event_count FROM agent_chat_task WHERE task_id = #{taskId} FOR UPDATE")
    Long selectEventCountForUpdate(@Param("taskId") String taskId);

    @Select("SELECT snapshot_event_id FROM agent_chat_task WHERE task_id = #{taskId}")
    String selectSnapshotEventId(@Param("taskId") String taskId);

    /** 内容与游标原子推进；event_count 只统计成功发布到 Redis 的事件。 */
    @Update("""
        UPDATE agent_chat_task
        SET event_count = event_count + 1,
            snapshot_revision = snapshot_revision + 1,
            snapshot_content = CONCAT(COALESCE(snapshot_content, ''), #{delta}),
            snapshot_event_id = #{eventId}
        WHERE task_id = #{taskId}
        """)
    int projectEvent(@Param("taskId") String taskId,
                     @Param("delta") String delta,
                     @Param("eventId") String eventId);

    /** 服务进程异常重启后的遗留任务（QUEUED / RUNNING 且长时间无心跳）。 */
    @Select("""
        SELECT task_id FROM agent_chat_task
        WHERE status IN ('QUEUED', 'RUNNING') AND updated_at < NOW(3) - INTERVAL 30 MINUTE
        LIMIT 100
        """)
    List<String> selectStaleTaskIds();

    /** 条件更新保证并发下只有一个清理者命中，调用方据返回值决定是否补发终态事件。 */
    @Update("""
        UPDATE agent_chat_task
        SET status = 'FAILED', finished_at = NOW(3)
        WHERE task_id = #{taskId} AND status IN ('QUEUED', 'RUNNING')
          AND updated_at < NOW(3) - INTERVAL 30 MINUTE
        """)
    int markStaleFailed(@Param("taskId") String taskId);
}
