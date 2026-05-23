package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体追踪事件实体 —— 对应 agent_trace_event 表
 */
@Data
@NoArgsConstructor
public class AgentTraceEvent {
    private Long id;
    private String conversationId;
    private String traceId;
    private Long userId;
    private String agentMode;
    private String eventType;
    private Integer sequenceNum;
    private String payload;
    private LocalDateTime createTime;
}
