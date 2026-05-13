package com.smartorder.edu.agent.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 意图路由结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentRouteResult {

    private String intentType;

    private String complexity;

    private AgentRunMode runMode;

    private List<String> requiredTools;

    private String reason;
}
