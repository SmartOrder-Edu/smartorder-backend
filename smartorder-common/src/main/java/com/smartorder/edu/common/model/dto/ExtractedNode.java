package com.smartorder.edu.common.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

/**
 * LLM 提取的知识节点
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedNode {

    /**
     * 节点名称（知识点名称）
     */
    private String name;

    /**
     * 节点描述
     */
    private String description;

    /**
     * 节点分类（如：概念、定理、公式、方法等）
     */
    private String category;

    /**
     * 重要程度（1-5）
     */
    private Integer importance;

    private List<Long> sourceChunkIds;

    private List<Integer> sourceChunkIndexes;

    private List<String> sourceTitlePaths;
}
