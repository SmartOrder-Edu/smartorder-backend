package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学习计划创建结果 — 由 LearningPlanCreationTool 返回
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanCreationResult {
    private Long spaceId;
    private String spaceName;
    private String spaceType;
    private int nodeCount;
    private int taskCount;
    private int kgNodeCount;
    private String startDate;
    private String targetEndDate;
}
