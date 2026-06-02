package com.smartorder.edu.common.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * LLM 提取的知识关系
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedRelation {

    /**
     * 源节点名称
     */
    private String sourceName;

    /**
     * 目标节点名称
     */
    private String targetName;

    /**
     * 关系类型
     * PREREQUISITE_OF - 前置知识
     * RELATED_TO - 相关关系
     * PART_OF - 包含关系
     * CAUSE_EFFECT - 因果关系
     * COMPARE_WITH - 比较关系
     * STEP_OF - 步骤关系
     * RECOMMEND - 推荐关系
     * DEPENDS_ON - 依赖关系
     */
    private String relationType;

    /**
     * 关系权重（0.0-1.0）
     */
    private Double weight;

    /**
     * 关系描述（可选）
     */
    private String description;
}
