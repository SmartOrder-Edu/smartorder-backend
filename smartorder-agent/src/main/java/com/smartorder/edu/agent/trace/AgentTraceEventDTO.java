package com.smartorder.edu.agent.trace;

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

    private String traceId;

    private String conversationId;

    private Long userId;

    private String mode;

    private String eventType;

    private Integer seq;

    private Long timestamp;

    private Object payload;
}
