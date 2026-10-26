package com.smartorder.edu.agent.repository.impl;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 已确认存在历史事件后，Redis 丢失不能以相同世代偷偷创建新流。 */
class AgentChatEventLogTest {
    @Test
    void rejectsMissingStreamWithoutPublishingNewEvent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("task-1"))).thenReturn(3L);
        when(redis.hasKey("agent:events:task-1")).thenReturn(false);

        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AgentChatEventLog log = new AgentChatEventLog(redis, jdbc, manager);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> log.append("task-1", "agent.text.delta", "新片段"));
        assertEquals("EVENT_LOG_UNAVAILABLE", error.getMessage());
        verify(redis, never()).opsForStream();
        verify(jdbc).update(startsWith("UPDATE agent_chat_task SET stream_started"), eq("task-1"));
    }

    /** 首条 XADD 已尝试而快照尚无事件时，丢失的 Stream 也不能以同世代重建。 */
    @Test
    void rejectsMissingStreamAfterFirstUnprojectedAttempt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("task-1"))).thenReturn(0L);
        when(redis.hasKey("agent:events:task-1")).thenReturn(false);

        AgentChatEventLog log = new AgentChatEventLog(redis, jdbc, manager);
        assertEquals("EVENT_LOG_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> log.append("task-1", "agent.text.delta", "新片段")).getMessage());
        verify(redis, never()).opsForStream();
    }
}
