package com.smartorder.edu.agent.repository.impl;

import com.smartorder.edu.agent.config.AgentSseProperties;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis Stream 与业务 seq 的映射：写入 ID 必须为 0-seq，after=0 必须从 0-0 开始读。
 */
class RedisAgentTaskEventStoreTest {

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final RedisAgentTaskEventStore store;

    RedisAgentTaskEventStoreTest() {
        when(redis.opsForStream()).thenReturn(streamOps);
        store = new RedisAgentTaskEventStore(redis, new AgentSseProperties());
    }

    @Test
    void shouldWriteExplicitStreamIdAlignedWithSeq() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("0-1"));

        store.appendEvent("task-1", AgentTaskEvent.builder()
                .seq(1)
                .eventName("agent.text.delta")
                .data("{}")
                .timestamp(1L)
                .build());

        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertEquals("0-1", captor.getValue().getId().getValue());
        assertEquals("agent:task:task-1:events", captor.getValue().getStream());
    }

    @Test
    void shouldReadFromZeroZeroWhenAfterIsZero() {
        when(streamOps.read(any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(List.of());

        store.readAfter("task-1", 0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<StreamOffset<String>> captor = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streamOps).read(any(StreamReadOptions.class), captor.capture());
        assertEquals("agent:task:task-1:events", captor.getValue().getKey());
        assertEquals("0-0", captor.getValue().getOffset().getOffset());
    }
}