package com.smartorder.edu.agent.route;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 意图类型枚举——与 AgentRunMode 解耦
 * <p>
 * 路由策略根据意图类型选择执行模式：
 * 简单意图（KNOWLEDGE_QA, PROBLEM_HELP, DIAGRAM_GENERATION, UNKNOWN）→ REACT
 * 复杂意图（GOAL_PLANNING, PLAN_CONFIRMATION, RESOURCE_GENERATION）→ PLAN_AND_EXECUTE
 */
@Getter
@AllArgsConstructor
public enum IntentType {

    KNOWLEDGE_QA("知识问答", "简单事实性或定义性问题"),
    PROBLEM_HELP("习题答疑", "习题解答、概念讲解、学习辅导"),
    DIAGRAM_GENERATION("图表生成", "生成 Mermaid、流程图、架构图、时序图等格式化技术说明"),
    GOAL_PLANNING("目标规划", "用户表达学习目标，需要制定学习计划"),
    PLAN_CONFIRMATION("确认创建计划", "用户确认已生成的规划草稿"),
    RESOURCE_GENERATION("资源生成", "生成 PPT、测试题、复习资料等"),
    PROGRESS_REVIEW("进度回顾", "查看学习进度和分析"),
    TASK_MANAGEMENT("任务管理", "创建/更新/管理学习任务"),
    UNKNOWN("未知意图", "无法明确分类，需要澄清或兜底");

    private final String chineseName;
    private final String description;
}
