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

    /** 可恢复事件协议版本，前端据此拒绝未知格式。 */
    @Builder.Default
    private Integer schemaVersion = 1;

    private String traceId;
    /** 可恢复任务 ID 与流世代，只在新任务接口产生。 */
    private String taskId;
    private String streamVersion;
    private String messageId;
    private String chunkId;
    private String content;

    private String conversationId;

    private Long userId;

    private String mode;

    private String eventType;

    private Integer seq;

    private Long timestamp;

    private Object payload;
}
