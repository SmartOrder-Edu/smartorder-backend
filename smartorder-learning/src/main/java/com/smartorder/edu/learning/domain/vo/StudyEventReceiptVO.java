package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StudyEventReceiptVO {
    private String eventId;
    private String sessionId;
    private String eventType;
    private String status;
    private Long acceptedAtMs;
}
