package com.smartorder.edu.agent.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 子智能体执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentResult {

    private String taskId;

    private String agentName;

    private Boolean success;

    private String summary;

    private Object data;

    private String errorMessage;
}
