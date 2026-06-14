package com.smartorder.edu.learning.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PathProgress {
    private Integer completedNodes;
    private Integer totalNodes;
    private Long currentNodeId;
    private Integer percent;
}
