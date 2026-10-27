package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话任务的<b>运行时状态</b>——Redis 里的那一份，也是跨进程唯一可见的那一份。
 *
 * <p><b>为什么需要它</b>：任务状态以前只活在创建它的 JVM 里（{@code ConcurrentHashMap}），
 * 于是"这个任务还在跑吗"「谁在跑」「用户取消了吗」这三个问题只有本进程答得上来：
 * 进程重启 / 请求打到另一个节点就只能靠 MySQL 猜，取消请求打错节点就静默失效。
 * 把这些字段搬进 Redis 后，状态是共享的，本机内存不再是任何结论的依据。</p>
 *
 * <p><b>与 MySQL 的分工</b>：</p>
 * <ul>
 *   <li>{@code agent_chat_task}：<b>落库权威</b>——任务存在性、消息归属、终态、审计，重启后仍在；</li>
 *   <li>本对象（Redis Hash）：<b>运行时权威</b>——实时状态、seq 水位、取消标记、中断标记、
 *       执行者节点；有 TTL，过期即"运行时已散场"，但库里仍可查；</li>
 *   <li>本机 {@code AgentTask}：<b>只放不可序列化的东西</b>——SSE 订阅者与内存事件日志。</li>
 * </ul>
 *
 * <p><b>字段为什么全是 String/long</b>：Redis Hash 直接按字符串存，
 * 读回来再解析，避免引入序列化器；枚举以 {@code name()} 落地，坏值按 RUNNING 容错。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskState {

    private String taskId;

    /** 归属用户——订阅/查询/取消都必须校验，不能把 taskId 当授权凭证 */
    private Long userId;

    /** 任务请求幂等键 */
    private String taskRequestId;

    private String conversationId;

    private String traceId;

    private Long userMessageId;

    private Long assistantMessageId;

    private Integer assistantSequenceNo;

    private String title;

    /** {@link AgentTaskStatus#name()} */
    private String status;

    /** 已产生事件的最大 seq；运行期由心跳刷新，终态时是最终水位 */
    private long lastSeq;

    private String errorMessage;

    private long createdAt;

    /** 最后一次心跳时间，用于"任务行已预留但还没绑定消息"的启动宽限判定 */
    private long updatedAt;

    private long finishedAt;

    /** 协作式取消标记：任意节点都可以置位，执行节点轮询到后自行退出 */
    private boolean cancelRequested;

    /** 事件是否完整可回放；异步归档失败时置 false */
    @Builder.Default
    private boolean replayable = true;

    /** 当前执行者节点 ID——用于判断"任务还活着，只是不在我这台机器上" */
    private String nodeId;

    public AgentTaskStatus statusEnum() {
        AgentTaskStatus parsed = AgentTaskStatus.fromName(status);
        return parsed != null ? parsed : AgentTaskStatus.RUNNING;
    }

    public boolean isTerminal() {
        return statusEnum().isTerminal();
    }

    /**
     * 转成 MySQL 元数据形状，供归档回放、状态查询这些"只需要只读快照"的路径复用。
     */
    public AgentTaskMeta toMeta() {
        return AgentTaskMeta.builder()
                .taskId(taskId)
                .conversationId(conversationId)
                .userId(userId)
                .traceId(traceId)
                .taskRequestId(taskRequestId)
                .status(status)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .finishedAt(finishedAt)
                .lastSeq(lastSeq)
                .userMessageId(userMessageId)
                .assistantMessageId(assistantMessageId)
                .assistantSequenceNo(assistantSequenceNo)
                .title(title)
                .errorMessage(errorMessage)
                .replayable(replayable)
                .build();
    }
}
