package com.smartorder.edu.common.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 知识图谱提取结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGraphExtractResult {

    /**
     * 提取的知识节点列表
     */
    private List<ExtractedNode> nodes;

    /**
     * 提取的知识关系列表
     */
    private List<ExtractedRelation> relations;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    public static KnowledgeGraphExtractResult success(List<ExtractedNode> nodes, List<ExtractedRelation> relations) {
        return new KnowledgeGraphExtractResult(nodes, relations, true, null);
    }

    public static KnowledgeGraphExtractResult failure(String errorMessage) {
        return new KnowledgeGraphExtractResult(null, null, false, errorMessage);
    }
}
