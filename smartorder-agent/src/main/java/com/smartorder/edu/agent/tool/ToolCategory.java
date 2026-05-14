package com.smartorder.edu.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具分类枚举
 */
@Getter
@AllArgsConstructor
public enum ToolCategory {

    PROFILE("学生档案"),
    PROGRESS("学习进度"),
    KNOWLEDGE("知识查询"),
    PLAN("学习规划"),
    RESOURCE("资源生成"),
    QUIZ("测评生成"),
    TASK("任务管理"),
    SEARCH("搜索工具");

    private final String description;
}
