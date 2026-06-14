package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PathEdgeVO {
    private Long id;
    private Long spaceId;
    private Long fromNodeId;
    private Long toNodeId;
    private String edgeType;
}
