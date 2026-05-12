package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LearningPathEdge {
    private Long id;
    private Long spaceId;
    private Long fromNodeId;
    private Long toNodeId;
    private String edgeType;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
