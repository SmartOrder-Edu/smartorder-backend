package com.smartorder.edu.agent.stream;

import com.smartorder.edu.agent.mapper.AgentChatTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 已确认存在历史事件后，Redis 丢失不能以相同世代偷偷创建新流。 */
class AgentEventLogTest {
    /** 确认响应写入独立 Redis 键并设置有限 TTL，支持网络结果不明时查证。 */
    @Test
    void cachesTaskReceiptWithStableIds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AgentEventLog log = new AgentEventLog(redis, mock(AgentChatTaskMapper.class),
                mock(PlatformTransactionManager.class));

        log.cacheReceipt("task-1", Map.of("taskId", "task-1", "messageId", "message-1"));

        verify(values).set(eq("agent:task:receipt:task-1"),
                contains("\"messageId\":\"message-1\""), eq(Duration.ofHours(48)));
    }
    @Test
    void rejectsMissingStreamWithoutPublishingNewEvent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AgentChatTaskMapper taskMapper = mock(AgentChatTaskMapper.class);
        // 已写入过事件但 Redis Stream 丢失：世代内不允许重建。
        when(taskMapper.selectEventCountForUpdate("task-1")).thenReturn(3L);
        when(redis.hasKey("agent:events:task-1")).thenReturn(false);

        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AgentEventLog log = new AgentEventLog(redis, taskMapper, manager);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> log.append("task-1", "agent.text.delta", "新片段"));
        assertEquals("EVENT_LOG_UNAVAILABLE", error.getMessage());
        verify(redis, never()).opsForStream();
        verify(taskMapper).markStreamStarted("task-1");
    }

    /** 首条 XADD 已尝试而快照尚无事件时，丢失的 Stream 也不能以同世代重建。 */
    @Test
    void rejectsMissingStreamAfterFirstUnprojectedAttempt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AgentChatTaskMapper taskMapper = mock(AgentChatTaskMapper.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(taskMapper.selectEventCountForUpdate("task-1")).thenReturn(0L);
        when(redis.hasKey("agent:events:task-1")).thenReturn(false);

        AgentEventLog log = new AgentEventLog(redis, taskMapper, manager);
        assertEquals("EVENT_LOG_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> log.append("task-1", "agent.text.delta", "新片段")).getMessage());
        verify(redis, never()).opsForStream();
    }
}
