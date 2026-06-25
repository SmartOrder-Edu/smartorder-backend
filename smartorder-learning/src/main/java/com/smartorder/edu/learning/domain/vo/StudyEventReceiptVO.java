package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class StudyEventReceiptVO {
    private String eventId;
    private String sessionId;
    private String eventType;
    private String status;
    private Long acceptedAtMs;
    private List<StudyTeachingActionVO> actions = new ArrayList<>();
}
