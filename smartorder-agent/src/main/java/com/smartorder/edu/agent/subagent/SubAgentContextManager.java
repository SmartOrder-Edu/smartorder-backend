package com.smartorder.edu.agent.subagent;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子智能体上下文管理器——每个子智能体独立 memoryKey
 */
@Component
public class SubAgentContextManager {

    private final Map<String, ChatMemory> memoryMap = new ConcurrentHashMap<>();

    private static final int MAX_MESSAGES = 10;

    /**
     * 获取或创建子智能体的隔离记忆
     */
    public ChatMemory getOrCreateMemory(String agentName, String taskId) {
        String key = agentName + ":" + taskId;
        return memoryMap.computeIfAbsent(key,
                k -> MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES));
    }

    /**
     * 清理子智能体记忆
     */
    public void clear(String agentName, String taskId) {
        String key = agentName + ":" + taskId;
        memoryMap.remove(key);
    }
}
