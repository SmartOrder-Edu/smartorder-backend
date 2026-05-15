package com.smartorder.edu.agent.route;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 运行模式枚举
 */
@Getter
@AllArgsConstructor
public enum AgentRunMode {

    REACT("REACT", "普通答疑 / 复杂学习问题"),
    PLAN_EXECUTE("PLAN_EXECUTE", "目标规划 / 学习空间创建"),
    MULTI_AGENT("MULTI_AGENT", "复杂资源生成 / 多任务协作");

    private final String code;
    private final String description;
}
