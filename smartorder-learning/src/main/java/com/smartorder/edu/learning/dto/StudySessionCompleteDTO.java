package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class StudySessionCompleteDTO {
    private Integer actualMinutes;
    private Integer focusLevel;
    private Integer mood;
    private Integer selfRating;
    private String notes;
    private Map<String, Object> payload;
}
