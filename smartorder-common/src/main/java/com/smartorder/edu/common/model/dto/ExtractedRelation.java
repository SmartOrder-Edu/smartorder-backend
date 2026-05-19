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
     * 关系类型（PREREQUISITE_OF, RELATED_TO, PART_OF）
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
