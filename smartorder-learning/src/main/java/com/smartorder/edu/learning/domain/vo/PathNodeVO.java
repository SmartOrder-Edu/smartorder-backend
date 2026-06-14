package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PathNodeVO {
    private Long id;
    private Long spaceId;
    private String title;
    private String description;
    private String nodeType;
    private Integer estimatedDurationMinutes;
    private String difficultyLevel;
    private Integer sortOrder;
    private Boolean isMilestone;
    private String status;
}
