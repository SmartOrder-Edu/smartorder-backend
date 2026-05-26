package com.smartorder.edu.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具分类枚举
 *
 * 风险等级：
 * - READ：读取画像、查询任务、查询知识
 * - WRITE_DRAFT：生成规划草稿、生成资源草稿（无副作用）
 * - WRITE_CONFIRMED：创建学习空间、创建任务、保存测评（有副作用，需确认门）
 */
@Getter
@AllArgsConstructor
public enum ToolCategory {

    PROFILE("学生档案", RiskLevel.READ),
    PROGRESS("学习进度", RiskLevel.READ),
    KNOWLEDGE("知识查询", RiskLevel.READ),
    PLAN("学习规划", RiskLevel.WRITE_DRAFT),
    RESOURCE("资源生成", RiskLevel.WRITE_DRAFT),
    QUIZ("测评生成", RiskLevel.WRITE_DRAFT),
    TASK("任务管理", RiskLevel.WRITE_CONFIRMED),
    SEARCH("搜索工具", RiskLevel.READ);

    private final String description;
    private final RiskLevel riskLevel;

    /**
     * 工具风险等级
     */
    @Getter
    @AllArgsConstructor
    public enum RiskLevel {
        READ("只读，无副作用"),
        WRITE_DRAFT("写草稿，无最终副作用"),
        WRITE_CONFIRMED("写已确认数据，有副作用，需确认门");

        private final String description;
    }
}
