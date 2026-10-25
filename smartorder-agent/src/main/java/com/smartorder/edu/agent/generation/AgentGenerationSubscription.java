package com.smartorder.edu.agent.generation;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 一条 SSE 订阅（一次“连接”）。
 *
 * <p>订阅只负责把事件写到某个 {@link AgentEventSink}，并记录最后成功发送的 seq。
 * 连接断开时只需要丢弃订阅本身，生成任务继续执行——这就是“生成与连接解耦”的落点。</p>
 */
@Slf4j
public class AgentGenerationSubscription {

    @Getter
    private final String generationId;

    /** 客户端声明的已应用游标：服务端只补发 seq 大于它的事件 */
    @Getter
    private final long afterSeq;

    private final AgentEventSink sink;

    @Getter
    private volatile long lastSentSeq;

    @Getter
    private volatile boolean closed;

    /** 是否已经完成过连接（避免重复 complete） */
    private volatile boolean completed;

    public AgentGenerationSubscription(String generationId, long afterSeq, AgentEventSink sink) {
        this.generationId = generationId;
        this.afterSeq = afterSeq;
        this.sink = sink;
        this.lastSentSeq = afterSeq;
    }

    /**
     * 投递一条事件。返回 false 表示连接已废，调用方应把订阅摘掉。
     */
    public boolean deliver(AgentGenerationEvent event) {
        if (closed) {
            return false;
        }
        try {
            sink.sendEvent(event);
            lastSentSeq = event.getSeq();
            return true;
        } catch (Exception e) {
            log.info("[GenerationSub] 事件发送失败，连接断开: generationId={}, seq={}, reason={}",
                    generationId, event.getSeq(), e.getMessage());
            closed = true;
            return false;
        }
    }

    /**
     * 发送心跳；返回 false 表示连接已不可用。
     */
    public boolean sendHeartbeat() {
        if (closed) {
            return false;
        }
        try {
            sink.sendHeartbeat();
            return true;
        } catch (Exception e) {
            log.debug("[GenerationSub] 心跳失败，连接断开: generationId={}, reason={}",
                    generationId, e.getMessage());
            closed = true;
            return false;
        }
    }

    /**
     * 关闭连接。即使此前发送失败（closed=true）也要把底层 emitter 收掉，避免连接悬挂。
     */
    public synchronized void complete() {
        closed = true;
        if (completed) {
            return;
        }
        completed = true;
        sink.complete();
    }

    /**
     * 异常关闭连接。响应已经提交后，回放失败只能走这里收尾。
     */
    public synchronized void completeWithError(Throwable error) {
        closed = true;
        if (completed) {
            return;
        }
        completed = true;
        sink.completeWithError(error);
    }

    public void close() {
        complete();
    }
}
