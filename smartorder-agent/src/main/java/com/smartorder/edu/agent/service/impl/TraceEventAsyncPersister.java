package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.dto.AgentTraceEventDTO;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Trace 事件异步持久化器 —— 不阻塞 SSE 推送，后台写入 agent_trace_event 表
 */
@Slf4j
@Component
public class TraceEventAsyncPersister {

    private final AgentTraceEventMapper mapper;

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000),
            r -> {
                Thread t = new Thread(r, "trace-persist");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public TraceEventAsyncPersister(AgentTraceEventMapper mapper) {
        this.mapper = mapper;
    }

    public void persistAsync(AgentTraceEventDTO dto) {
        executor.execute(() -> {
            try {
                AgentTraceEvent entity = new AgentTraceEvent();
                entity.setConversationId(dto.getConversationId());
                entity.setTraceId(dto.getTraceId());
                entity.setUserId(dto.getUserId());
                entity.setAgentMode(dto.getMode());
                entity.setEventType(dto.getEventType());
                entity.setSequenceNum(dto.getSeq());
                entity.setPayload(JSON.toJSONString(dto.getPayload()));

                mapper.insert(entity);
            } catch (Exception e) {
                log.error("[TracePersist] 异步落库失败: traceId={}, eventType={}, seq={}",
                        dto.getTraceId(), dto.getEventType(), dto.getSeq(), e);
            }
        });
    }
}
