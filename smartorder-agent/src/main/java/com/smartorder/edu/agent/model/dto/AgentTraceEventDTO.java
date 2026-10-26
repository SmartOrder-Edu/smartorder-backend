package com.smartorder.edu.agent.model.dto;

import com.smartorder.edu.agent.runtime.AgentGeneration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 SSE 事件 DTO——所有事件必须包含以下字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceEventDTO {

    /**
     * 所属生成任务 ID。生成事件由 AgentGeneration 分配 seq 并写入回放日志；
     * 非生成入口（如计划确认）该字段为空。
     */
    private String generationId;

    private String traceId;

    private String conversationId;

    private Long userId;

    private String mode;

    private String eventType;

    private Integer seq;

    private Long timestamp;

    private Object payload;
}
