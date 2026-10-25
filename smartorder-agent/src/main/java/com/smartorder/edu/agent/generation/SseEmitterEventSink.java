package com.smartorder.edu.agent.generation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 {@link SseEmitter} 的事件出口。
 *
 * <p>每条事件都带 {@code id: seq}，客户端可直接把它作为断点游标；
 * 心跳用 SSE 注释行，既不进入业务游标，也不会被前端当成事件解析。</p>
 */
@Slf4j
public class SseEmitterEventSink implements AgentEventSink {

    private final SseEmitter emitter;

    public SseEmitterEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void sendEvent(AgentGenerationEvent event) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name(event.getEventName())
                .data(event.getData());
        // seq=0 是“即时事件”（如异步标题），不进入业务游标，也就不下发 SSE id
        if (event.getSeq() > 0) {
            builder.id(String.valueOf(event.getSeq()));
        }
        emitter.send(builder);
    }

    @Override
    public void sendHeartbeat() throws Exception {
        emitter.send(SseEmitter.event().comment("ping"));
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[GenerationSse] 连接已完成或已断开: {}", e.getMessage());
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception e) {
            log.debug("[GenerationSse] 连接已关闭: {}", e.getMessage());
        }
    }

    public SseEmitter getEmitter() {
        return emitter;
    }
}
