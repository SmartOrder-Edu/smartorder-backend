package com.smartorder.edu.common.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 知识图谱展示 VO
 */
@Data
public class KnowledgeGraphVO {

    private Long fileId;
    private List<NodeVO> nodes;
    private List<EdgeVO> edges;

    @Data
    public static class NodeVO {
        private String id;
        private String name;
        private String description;
        private String category;
        private Integer importance;
    }

    @Data
    public static class EdgeVO {
        private String source;
        private String target;
        private String relationType;
    }
}
