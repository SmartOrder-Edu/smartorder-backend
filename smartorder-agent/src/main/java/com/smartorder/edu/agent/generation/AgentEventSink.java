package com.smartorder.edu.agent.generation;

/**
 * 事件出口抽象——把“生成事件该发给谁”与 SSE 的具体实现解耦。
 *
 * <p>生产实现是 {@link SseEmitterEventSink}；单元测试用记录型实现即可验证
 * 重放顺序、游标过滤和去重，不需要真的建立 HTTP 连接。</p>
 */
public interface AgentEventSink {

    /**
     * 发送一条事件。实现方抛出异常表示连接已不可用。
     */
    void sendEvent(AgentGenerationEvent event) throws Exception;

    /**
     * 发送 SSE 注释心跳（不进入业务游标）。
     */
    void sendHeartbeat() throws Exception;

    /**
     * 正常结束连接。
     */
    void complete();

    /**
     * 异常结束连接。
     */
    void completeWithError(Throwable error);
}
