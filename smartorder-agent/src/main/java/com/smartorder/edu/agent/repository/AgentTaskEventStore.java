package com.smartorder.edu.agent.repository;

import com.smartorder.edu.agent.repository.impl.InMemoryAgentTaskEventStore;
import com.smartorder.edu.agent.repository.impl.RedisAgentTaskEventStore;
import com.smartorder.edu.agent.runtime.AgentTask;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;

import java.util.List;

/**
 * 任务事件归档存储（Redis Stream 消息片段）。
 *
 * <p>职责边界：存储只负责“按 taskId 顺序追加事件”和“按 seq 读取”，
 * 不参与实时推送；实时推送永远走本机 {@link AgentTask} 的内存日志。
 * 这样即使归档写失败，当前进程的断线重连依然完整；失败只会影响跨进程回放。</p>
 *
 * <p>默认实现是 Redis Stream（{@link RedisAgentTaskEventStore}），
 * 单测与本地无 Redis 场景使用 {@link InMemoryAgentTaskEventStore}。
 * 任务元数据不在本接口中，统一落在 MySQL {@code agent_chat_task}。</p>
 */
public interface AgentTaskEventStore {

    /**
     * 追加一条事件。实现必须保证同一任务内的追加顺序与 seq 顺序一致。
     */
    void appendEvent(String taskId, AgentTaskEvent event);

    /**
     * 读取 seq 严格大于 afterSeq 的事件，按 seq 升序。
     */
    List<AgentTaskEvent> readAfter(String taskId, long afterSeq);

    /**
     * 读取 seq 严格大于 afterSeq 的第一条事件；没有返回 null。
     *
     * <p>订阅的同步校验阶段用它判断“游标之后是否存在缺口”，避免为了一个游标检查
     * 把整个 backlog 拉到请求线程。默认实现退化为 {@link #readAfter}；
     * 生产用的 Redis Stream 实现覆盖为单条读取。</p>
     */
    default AgentTaskEvent readFirstAfter(String taskId, long afterSeq) {
        List<AgentTaskEvent> events = readAfter(taskId, afterSeq);
        return events.isEmpty() ? null : events.get(0);
    }

    /**
     * 归档中最后一条事件的 seq；没有事件返回 0。
     *
     * <p>运行中的 MySQL 元数据不会随每条事件更新 lastSeq（避免每个 token 一次 UPDATE），
     * 订阅校验与中断标记必须以 Stream 尾部为准，否则会把已有事件覆盖成重复 seq。</p>
     */
    default long lastEventSeq(String taskId) {
        List<AgentTaskEvent> events = readAfter(taskId, 0L);
        return events.isEmpty() ? 0L : events.get(events.size() - 1).getSeq();
    }
}
