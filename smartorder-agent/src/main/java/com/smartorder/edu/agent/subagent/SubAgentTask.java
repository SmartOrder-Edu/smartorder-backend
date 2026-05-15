package com.smartorder.edu.agent.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 子智能体任务定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentTask {

    private String taskId;

    private String agentName;

    private String taskDescription;

    private List<String> allowedTools;

    private Integer timeoutSeconds;
}
