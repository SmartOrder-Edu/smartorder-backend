package com.smartorder.edu.agent.runtime.store;

import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.AgentTaskState;

import java.util.Set;

/**
 * 对话任务<b>运行时状态</b>存储（生产实现是 Redis）。
 *
 * <p><b>职责边界</b>：本接口只回答"任务现在是什么状态、谁在执行、是否被取消/中断"，
 * 不保存事件正文（那是 {@link AgentTaskEventStore}），也不保存 SSE 连接
 * （SseEmitter 是 JVM 对象，只能留在本机）。</p>
 *
 * <p><b>为什么要有这个抽象</b>：{@code AgentTaskRegistry} 原来用
 * {@code ConcurrentHashMap} 保存"正在执行的任务"和"已补发中断的任务"，
 * 这两份数据本质都是<b>跨进程共享的状态</b>，放在 JVM 里就必然出现
 * "重启即丢失、多实例各说各话、取消打错节点就失效"。抽成存储后：
 * 生产用 Redis，单测/无 Redis 环境用内存实现，语义完全一致。</p>
 *
 * <p><b>一致性约定</b>：写入是"状态写通"（write-through）——调用方改完本地快照后
 * 立刻 {@link #save(AgentTaskState)}；读取不做本地缓存，永远问存储。
 * 只有事件追加这种高频路径例外：seq 由执行线程本地自增，靠 {@link #touch} 定期把水位刷进存储。</p>
 */
public interface AgentTaskStateStore {

    /** 本节点 ID（host + pid + 随机后缀），用于标记执行者与节点租约 */
    String nodeId();

    /**
     * 全量写入任务状态快照，并维护索引：
     * 非终态进"正在执行"集合，终态移出；中断态进"已中断"集合；会话索引指向最新任务。
     */
    void save(AgentTaskState state);

    /** 读取状态快照；不存在（未创建 / 已过 TTL）返回 null */
    AgentTaskState load(String taskId);

    /**
     * 心跳：刷新 TTL、更新 seq 水位与活跃时间。只写少量字段，避免整份覆盖。
     */
    void touch(String taskId, long lastSeq);

    /**
     * 置协作式取消标记。
     *
     * @return false 表示任务状态已不存在（已过期/从未创建），调用方应视为不可取消
     */
    boolean requestCancel(String taskId);

    /** 取消标记是否已置位 */
    boolean isCancelRequested(String taskId);

    /**
     * 抢占"中断事件已补发"标记（SETNX 语义）。
     *
     * @return true 表示本次调用是第一个抢到的，应该继续追加 error + done
     */
    boolean markInterruptedOnce(String taskId);

    /**
     * 释放中断标记。
     *
     * <p>抢占成功后如果追加事件失败，必须把标记放回去，否则这个任务以后再也不会被判定为中断——
     * 客户端将永远等不到终态。</p>
     */
    void releaseInterruptedMarker(String taskId);

    /**
     * 标记事件归档降级（不可完整回放）。
     *
     * @return true 表示首次标记，调用方可据此只打一条错误日志
     */
    boolean markReplayDegraded(String taskId);

    /** 删除运行时状态（事务 A 失败回滚占位任务时使用） */
    void remove(String taskId);

    /** "正在执行"的任务 ID 集合（按活跃时间升序，最久未心跳的在前） */
    Set<String> activeTaskIds();

    /** 已判定中断的任务 ID 集合（运维/排查用，同时也是跨节点去重的依据） */
    Set<String> interruptedTaskIds();

    /** 节点租约是否仍然有效——进程崩溃后租约过期，任务才能被判定为"执行者消失" */
    boolean isNodeAlive(String nodeId);

    /** 刷新本节点租约（由注册表心跳线程周期调用） */
    void refreshNodeLease();

    /**
     * 主动释放本节点租约（优雅停机时调用）。
     *
     * <p>崩溃只能靠 TTL 过期被发现（这是租约的本质）；但正常发布/重启没必要让
     * 客户端白等一个租约周期——这里直接把租约删掉，别的节点立刻就能判定
     * "执行者已经走了"并补发中断事件。注意<b>不要</b>顺手把任务改成终态：
     * 终态要靠 error + done 事件一起收敛，否则客户端会收到一条没有 done 的流。</p>
     */
    default void releaseNodeLease() {
    }

    /** 会话 → 最近一次任务 ID；没有返回 null */
    String latestTaskId(String conversationId);

    /**
     * 裁剪索引里早于给定时间的成员。
     *
     * <p>进程被 kill 时没有人会去清理索引，只有心跳线程顺手按时间裁剪，
     * "正在执行"集合才不会无限增长。内存实现无 TTL 概念，默认不处理。</p>
     */
    default void pruneIndexesBefore(long cutoffMillis) {
    }

    /**
     * 统一的状态写入：终态时一次性落 status/lastSeq/error/完成时间。
     */
    default void markTerminal(String taskId, AgentTaskStatus status, long lastSeq,
                              String errorMessage, Long assistantMessageId, boolean replayable) {
        AgentTaskState state = load(taskId);
        if (state == null) {
            return;
        }
        state.setStatus(status.name());
        state.setLastSeq(Math.max(state.getLastSeq(), lastSeq));
        state.setErrorMessage(errorMessage);
        state.setFinishedAt(System.currentTimeMillis());
        state.setUpdatedAt(state.getFinishedAt());
        if (assistantMessageId != null) {
            state.setAssistantMessageId(assistantMessageId);
        }
        state.setReplayable(state.isReplayable() && replayable);
        save(state);
    }
}
