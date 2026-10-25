package com.smartorder.edu.agent.generation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版归档实现——单机、单测、或本地没有 Redis 时使用。
 *
 * <p>语义与 Redis 实现保持一致：事件按 seq 追加、按 seq 过滤读取。
 * 进程退出即丢失，因此只适合开发/测试；生产默认走 Redis。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "memory")
public class InMemoryAgentGenerationEventStore implements AgentGenerationEventStore {

    private final Map<String, AgentGenerationMeta> metas = new ConcurrentHashMap<>();

    private final Map<String, List<AgentGenerationEvent>> events = new ConcurrentHashMap<>();

    private final Map<String, String> clientRequestIndex = new ConcurrentHashMap<>();

    private final Map<String, String> conversationIndex = new ConcurrentHashMap<>();

    @Override
    public void saveMeta(AgentGenerationMeta meta) {
        metas.put(meta.getGenerationId(), meta);
    }

    @Override
    public AgentGenerationMeta getMeta(String generationId) {
        return metas.get(generationId);
    }

    @Override
    public void updateState(String generationId, AgentGenerationStatus status, long lastSeq,
                            String errorMessage, Long assistantMessageId) {
        AgentGenerationMeta meta = metas.get(generationId);
        if (meta == null) {
            return;
        }
        synchronized (meta) {
            meta.setStatus(status.name());
            meta.setLastSeq(Math.max(meta.getLastSeq(), lastSeq));
            meta.setErrorMessage(errorMessage);
            if (assistantMessageId != null) {
                meta.setAssistantMessageId(assistantMessageId);
            }
            meta.setUpdatedAt(System.currentTimeMillis());
        }
    }

    @Override
    public void appendEvent(String generationId, AgentGenerationEvent event) {
        events.computeIfAbsent(generationId, k -> Collections.synchronizedList(new ArrayList<>())).add(event);
        AgentGenerationMeta meta = metas.get(generationId);
        if (meta != null) {
            synchronized (meta) {
                meta.setLastSeq(Math.max(meta.getLastSeq(), event.getSeq()));
            }
        }
    }

    @Override
    public List<AgentGenerationEvent> readAfter(String generationId, long afterSeq) {
        List<AgentGenerationEvent> all = events.get(generationId);
        if (all == null) {
            return List.of();
        }
        List<AgentGenerationEvent> result = new ArrayList<>();
        synchronized (all) {
            for (AgentGenerationEvent event : all) {
                if (event.getSeq() > afterSeq) {
                    result.add(event);
                }
            }
        }
        return result;
    }

    @Override
    public long lastEventSeq(String generationId) {
        List<AgentGenerationEvent> all = events.get(generationId);
        if (all == null) {
            return 0L;
        }
        synchronized (all) {
            return all.isEmpty() ? 0L : all.get(all.size() - 1).getSeq();
        }
    }

    @Override
    public void bindClientRequest(Long userId, String clientRequestId, String generationId) {
        clientRequestIndex.put(key(userId, clientRequestId), generationId);
    }

    @Override
    public String findGenerationByClientRequest(Long userId, String clientRequestId) {
        return clientRequestIndex.get(key(userId, clientRequestId));
    }

    @Override
    public void bindConversation(String conversationId, String generationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            conversationIndex.put(conversationId, generationId);
        }
    }

    @Override
    public String findActiveGeneration(String conversationId) {
        return conversationId == null ? null : conversationIndex.get(conversationId);
    }

    @Override
    public void updateConversation(String generationId, String conversationId) {
        AgentGenerationMeta meta = metas.get(generationId);
        if (meta != null && conversationId != null) {
            meta.setConversationId(conversationId);
        }
    }

    @Override
    public void markReplayDegraded(String generationId) {
        AgentGenerationMeta meta = metas.get(generationId);
        if (meta != null) {
            meta.setReplayable(false);
        }
    }

    private String key(Long userId, String clientRequestId) {
        return userId + ":" + clientRequestId;
    }
}
