package com.smartorder.edu.agent.trace;

import com.smartorder.edu.agent.route.AgentRunMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 运行时上下文——一次完整 Agent 调用的生命周期状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeContext {

    private String traceId;

    private String conversationId;

    private Long userId;

    private AgentRunMode mode;

    @Builder.Default
    private AtomicInteger sequence = new AtomicInteger(0);

    /**
     * 获取下一个事件序号
     */
    public int nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 当前序号（不自增）
     */
    public int currentSeq() {
        return sequence.get();
    }
}
