package com.smartorder.edu.knowledge.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class KnowledgeNodeMastery {
    private Long id;
    private Long userId;
    private Long nodeId;
    private Double masteryLevel;
    private Double confidence;
    private LocalDateTime lastAssessedAt;
    private Integer assessmentCount;
    private String evidenceSources;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
