package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.model.dto.AgentTaskSubscribeQuery;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务事件流的只读订阅服务。
 * <p>
 * 约定：订阅绝不启动 Agent、绝不创建任务。网络重连只能重新读取已有 Stream，
 * 读取失败时向客户端下发 {@code resync_required} 让其重新对齐快照。
 */
public interface AgentTaskStreamService {

    /**
     * 建立 SSE 订阅。
     * <p>
     * 归属校验与游标校验在调用线程内完成（因此鉴权失败仍能返回 HTTP 状态码），
     * 真正的读取循环提交到独立的流线程池执行。
     *
     * @param taskId      任务 ID
     * @param query       查询参数（起始游标、Stream 世代、消息 ID）
     * @param lastEventId 请求头 {@code Last-Event-ID}，优先级高于查询串游标
     * @param caller      当前调用者身份
     * @return 已挂载读取循环的 SSE 发射器
     */
    SseEmitter subscribe(String taskId, AgentTaskSubscribeQuery query, String lastEventId, AgentCaller caller);
}
