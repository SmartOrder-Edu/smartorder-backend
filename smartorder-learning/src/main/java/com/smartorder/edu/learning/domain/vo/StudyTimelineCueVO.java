package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class StudyTimelineCueVO {
    private String id;
    private String type;
    private Long startMs;
    private Long endMs;
    private Map<String, Object> payload = new HashMap<>();
}
