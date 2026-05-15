package com.smartorder.edu.agent.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 子智能体信箱——收集子智能体执行结果
 */
@Slf4j
@Component
public class SubAgentMailbox {

    private final ConcurrentMap<String, List<SubAgentResult>> sessionResults = new ConcurrentHashMap<>();

    /**
     * 投递结果
     */
    public void deliver(String sessionKey, SubAgentResult result) {
        sessionResults.computeIfAbsent(sessionKey, k -> new ArrayList<>()).add(result);
        log.info("[Mailbox] 收件: sessionKey={}, agent={}, success={}", sessionKey, result.getAgentName(), result.getSuccess());
    }

    /**
     * 获取所有结果
     */
    public List<SubAgentResult> getResults(String sessionKey) {
        return sessionResults.getOrDefault(sessionKey, List.of());
    }

    /**
     * 清理
     */
    public void clear(String sessionKey) {
        sessionResults.remove(sessionKey);
    }
}
