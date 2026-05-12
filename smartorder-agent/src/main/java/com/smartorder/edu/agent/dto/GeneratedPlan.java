package com.smartorder.edu.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 生成的学习计划 JSON 结构体
 * 由 Agent 调用 LLM 后解析得到，包含完整的学习空间规划
 */
@Data
@NoArgsConstructor
public class GeneratedPlan {

    /** 学习空间名称 */
    @JsonProperty("spaceName")
    private String spaceName;

    /** 空间类型：course/exam/project/career/competition */
    @JsonProperty("type")
    private String type;

    /** 空间描述 */
    @JsonProperty("description")
    private String description;

    /** 学习目标列表 */
    @JsonProperty("goals")
    private List<String> goals;

    /** 计划开始日期 (yyyy-MM-dd) */
    @JsonProperty("startDate")
    private String startDate;

    /** 目标完成日期 (yyyy-MM-dd) */
    @JsonProperty("targetEndDate")
    private String targetEndDate;

    /** 学习路径节点列表 */
    @JsonProperty("nodes")
    private List<PlanNode> nodes;

    /** 学习路径边列表（依赖关系） */
    @JsonProperty("edges")
    private List<PlanEdge> edges;

    /** 任务列表 */
    @JsonProperty("tasks")
    private List<PlanTask> tasks;

    /** 知识图谱节点 */
    @JsonProperty("kgNodes")
    private List<PlanKgNode> kgNodes;

    // ---- 内部类 ----

    @Data
    @NoArgsConstructor
    public static class PlanNode {
        @JsonProperty("title")
        private String title;
        @JsonProperty("description")
        private String description;
        @JsonProperty("nodeType")
        private String nodeType;       // module/stage/objective/knowledge_point
        @JsonProperty("estimatedDurationMinutes")
        private Integer estimatedDurationMinutes;
        @JsonProperty("difficultyLevel")
        private Integer difficultyLevel;  // 1-5
        @JsonProperty("sortOrder")
        private Integer sortOrder;
        @JsonProperty("isMilestone")
        private Integer isMilestone;   // 0 or 1
    }

    @Data
    @NoArgsConstructor
    public static class PlanEdge {
        @JsonProperty("fromSortOrder")
        private Integer fromSortOrder;
        @JsonProperty("toSortOrder")
        private Integer toSortOrder;
        @JsonProperty("edgeType")
        private String edgeType;       // prerequisite_of / recommended_before
    }

    @Data
    @NoArgsConstructor
    public static class PlanTask {
        @JsonProperty("title")
        private String title;
        @JsonProperty("description")
        private String description;
        @JsonProperty("taskType")
        private String taskType;       // daily/milestone/exam/review
        @JsonProperty("priority")
        private Integer priority;      // 1-5
        @JsonProperty("nodeSortOrder")
        private Integer nodeSortOrder; // 关联 path node 的 sortOrder
        @JsonProperty("dayOffset")
        private Integer dayOffset;     // 从 startDate 起第几天
        @JsonProperty("estimatedDurationMinutes")
        private Integer estimatedDurationMinutes;
    }

    @Data
    @NoArgsConstructor
    public static class PlanKgNode {
        @JsonProperty("name")
        private String name;
        @JsonProperty("description")
        private String description;
        @JsonProperty("category")
        private String category;       // concept/skill/topic/method/fact
        @JsonProperty("importance")
        private Integer importance;    // 1-5
    }
}
