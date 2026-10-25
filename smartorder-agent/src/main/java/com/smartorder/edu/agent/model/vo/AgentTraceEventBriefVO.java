package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量 trace 查询的精简事件 DTO。
 *
 * <p>不返回 id / userId / conversationId / agentMode 等重复字段，降低长会话首屏体积；
 * payload 仍是原始 JSON 字符串，由前端按既有逻辑解析。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceEventBriefVO {

    private String traceId;

    private String eventType;

    private Integer seq;

    /** 事件产生时间（毫秒）；create_time 为空时为 null */
    private Long timestamp;

    /** 原始 payload JSON 字符串 */
    private String payload;
}
