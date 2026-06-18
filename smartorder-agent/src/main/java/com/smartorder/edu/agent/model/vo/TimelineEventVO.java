package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Timeline 事件 VO——标准化历史回放节点，前端直接渲染时间线
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEventVO {

    /** PLAN_CREATED | TASK_STARTED | TOOL_CALL | TASK_COMPLETED | GATE_PAUSED | GATE_ANSWERED | RUN_COMPLETED */
    private String eventType;

    private String taskId;

    private String description;

    /** started | completed | failed | paused | answered */
    private String status;

    /** 事件附加详情（toolName, question, answer, summary 等） */
    private Map<String, Object> details;

    private Long timestamp;

    private int seq;
}
