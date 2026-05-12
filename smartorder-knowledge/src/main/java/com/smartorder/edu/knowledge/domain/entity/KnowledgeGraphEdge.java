package com.smartorder.edu.knowledge.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class KnowledgeGraphEdge {
    private Long id;
    private Long spaceId;
    private Long fromNodeId;
    private Long toNodeId;
    private String relationType;
    private Double weight;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
