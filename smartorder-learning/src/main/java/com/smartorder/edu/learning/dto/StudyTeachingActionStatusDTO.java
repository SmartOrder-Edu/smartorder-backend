package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class StudyTeachingActionStatusDTO {
    private String status;
    private String errorMessage;
    private Map<String, Object> payload;
}
