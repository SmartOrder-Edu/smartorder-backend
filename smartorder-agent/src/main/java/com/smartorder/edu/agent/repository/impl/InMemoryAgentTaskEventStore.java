package com.smartorder.edu.agent.repository.impl;

import com.smartorder.edu.agent.repository.AgentTaskEventStore;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版事件归档——单机、单测、或本地没有 Redis 时使用。
 *
 * <p>语义与 Redis Stream 实现保持一致：事件按 seq 追加、按 seq 过滤读取。
 * 进程退出即丢失，因此只适合开发/测试；生产默认走 Redis Stream。
 * 任务元数据仍由 MySQL 仓储提供。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "memory")
public class InMemoryAgentTaskEventStore implements AgentTaskEventStore {

    private final Map<String, List<AgentTaskEvent>> events = new ConcurrentHashMap<>();

    @Override
    public void appendEvent(String taskId, AgentTaskEvent event) {
        events.computeIfAbsent(taskId, key -> Collections.synchronizedList(new ArrayList<>())).add(event);
    }

    @Override
    public List<AgentTaskEvent> readAfter(String taskId, long afterSeq) {
        List<AgentTaskEvent> all = events.get(taskId);
        if (all == null) {
            return List.of();
        }
        List<AgentTaskEvent> result = new ArrayList<>();
        synchronized (all) {
            for (AgentTaskEvent event : all) {
                if (event.getSeq() > afterSeq) {
                    result.add(event);
                }
            }
        }
        return result;
    }

    @Override
    public AgentTaskEvent readFirstAfter(String taskId, long afterSeq) {
        List<AgentTaskEvent> all = events.get(taskId);
        if (all == null) {
            return null;
        }
        synchronized (all) {
            for (AgentTaskEvent event : all) {
                if (event.getSeq() > afterSeq) {
                    return event;
                }
            }
        }
        return null;
    }

    @Override
    public long lastEventSeq(String taskId) {
        List<AgentTaskEvent> all = events.get(taskId);
        if (all == null) {
            return 0L;
        }
        synchronized (all) {
            return all.isEmpty() ? 0L : all.get(all.size() - 1).getSeq();
        }
    }
}
