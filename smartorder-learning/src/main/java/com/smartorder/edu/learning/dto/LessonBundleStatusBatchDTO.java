package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class LessonBundleStatusBatchDTO {
    private List<Long> pathNodeIds;
    private String spaceId;
    private String mode;
}
