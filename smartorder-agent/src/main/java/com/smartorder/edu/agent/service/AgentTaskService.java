package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.common.AgentCaller;
import com.smartorder.edu.agent.model.dto.AgentTaskCreateRequest;
import com.smartorder.edu.agent.model.entity.AgentChatTask;
import com.smartorder.edu.agent.model.vo.AgentCacheScopeVO;
import com.smartorder.edu.agent.model.vo.AgentTaskReceiptVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSnapshotVO;
import com.smartorder.edu.agent.model.vo.AgentTaskSummaryVO;

/**
 * 可恢复 Agent 对话任务的应用服务。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>写操作只有 {@link #create}：负责幂等仲裁、任务落库与后台调度；</li>
 *   <li>读操作全部只读：{@link #snapshot}、{@link #activeTask} 不创建任务、不触发 Agent；</li>
 *   <li>身份校验统一按"任务所有者 + 租户"进行，跨账号一律视为不存在。</li>
 * </ul>
 * 事件流的实时读取不属于本服务，见 {@link AgentTaskStreamService}。
 */
public interface AgentTaskService {

    /**
     * 创建任务并异步执行 Agent。
     * <p>
     * 同一 {@code requestId} 的重试返回原任务，不会重复执行；请求内容不同则拒绝。
     *
     * @param request 创建请求
     * @param caller  当前调用者身份
     * @return 任务回执（含 taskId / messageId / streamVersion）
     */
    AgentTaskReceiptVO create(AgentTaskCreateRequest request, AgentCaller caller);

    /**
     * 读取任务快照。
     *
     * @return 服务端正文、终态与已提交事件游标
     */
    AgentTaskSnapshotVO snapshot(String taskId, AgentCaller caller);

    /**
     * 查询会话最近一次任务，供刷新后恢复。
     *
     * @return 无任务时返回全空字段对象，便于前端判空
     */
    AgentTaskSummaryVO activeTask(String conversationId, AgentCaller caller);

    /** 当前身份的本地缓存隔离键。 */
    AgentCacheScopeVO cacheScope(AgentCaller caller);

    /**
     * 校验任务归属并返回任务行；不存在或不属于当前身份时抛出 404。
     * <p>
     * 供事件流订阅复用，避免订阅接口自行拼装鉴权条件。
     */
    AgentChatTask requireOwned(String taskId, AgentCaller caller);
}
