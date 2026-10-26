package com.smartorder.edu.agent.repository;

import com.smartorder.edu.agent.model.enums.AgentGenerationStatus;
import com.smartorder.edu.agent.repository.impl.InMemoryAgentGenerationEventStore;
import com.smartorder.edu.agent.repository.impl.RedisAgentGenerationEventStore;
import com.smartorder.edu.agent.runtime.AgentGeneration;
import com.smartorder.edu.agent.runtime.AgentGenerationEvent;
import com.smartorder.edu.agent.runtime.AgentGenerationMeta;

import java.util.List;

/**
 * 生成事件归档存储。
 *
 * <p>职责边界：存储只负责“按 generationId 顺序追加事件”和“按 seq 读取”，
 * 不参与实时推送；实时推送永远走本机 {@link AgentGeneration} 的内存日志。
 * 这样即使归档写失败，当前进程的断线重连依然完整；失败只会影响跨进程回放。</p>
 *
 * <p>默认实现是 Redis（{@link RedisAgentGenerationEventStore}），
 * 单测与本地无 Redis 场景使用 {@link InMemoryAgentGenerationEventStore}。</p>
 */
public interface AgentGenerationEventStore {

    /**
     * 保存/覆盖元数据。创建时调用一次。
     */
    void saveMeta(AgentGenerationMeta meta);

    /**
     * 读取元数据；不存在返回 null。
     */
    AgentGenerationMeta getMeta(String generationId);

    /**
     * 更新生命周期状态（终态回写、中断标记）。
     */
    void updateState(String generationId, AgentGenerationStatus status, long lastSeq,
                     String errorMessage, Long assistantMessageId);

    /**
     * 追加一条事件。实现必须保证同一 generation 内的追加顺序与 seq 顺序一致。
     */
    void appendEvent(String generationId, AgentGenerationEvent event);

    /**
     * 读取 seq 严格大于 afterSeq 的事件，按 seq 升序。
     */
    List<AgentGenerationEvent> readAfter(String generationId, long afterSeq);

    /**
     * 读取 seq 严格大于 afterSeq 的第一条事件；没有返回 null。
     *
     * <p>订阅的同步校验阶段用它判断“游标之后是否存在缺口”，避免为了一个游标检查
     * 把整个 backlog 拉到请求线程。默认实现退化为 {@link #readAfter}；
     * 生产用的 Redis 实现覆盖为 O(1) 单条读取。</p>
     */
    default AgentGenerationEvent readFirstAfter(String generationId, long afterSeq) {
        List<AgentGenerationEvent> events = readAfter(generationId, afterSeq);
        return events.isEmpty() ? null : events.get(0);
    }

    /**
     * 归档中最后一条事件的 seq；没有事件返回 0。
     *
     * <p>运行中的 Redis 元数据不会随每条事件更新 lastSeq（避免每个 token 一次 HSET），
     * 订阅校验与中断标记必须以归档尾部为准，否则会把已有事件覆盖成重复 seq。</p>
     */
    default long lastEventSeq(String generationId) {
        List<AgentGenerationEvent> events = readAfter(generationId, 0L);
        return events.isEmpty() ? 0L : events.get(events.size() - 1).getSeq();
    }

    /**
     * 记录“用户 + 幂等键 → generationId”的映射。
     */
    void bindClientRequest(Long userId, String clientRequestId, String generationId);

    String findGenerationByClientRequest(Long userId, String clientRequestId);

    /**
     * 记录“会话 → 当前生成”的映射，用于刷新页面后恢复。
     */
    void bindConversation(String conversationId, String generationId);

    /**
     * 会话 ID 在 openTurn 之后才确定，回填到生成元数据里。
     */
    void updateConversation(String generationId, String conversationId);

    String findActiveGeneration(String conversationId);

    /**
     * 标记该生成的事件不再保证完整（归档失败时调用）。
     */
    void markReplayDegraded(String generationId);
}
