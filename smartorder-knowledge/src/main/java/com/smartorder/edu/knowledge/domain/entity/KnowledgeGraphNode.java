package com.smartorder.edu.knowledge.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class KnowledgeGraphNode {
    private Long id;
    private Long spaceId;
    private String name;
    private String description;
    private String category;
    private Integer importance;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
