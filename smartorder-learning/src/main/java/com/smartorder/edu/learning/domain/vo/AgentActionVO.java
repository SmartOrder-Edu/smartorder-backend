package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AgentActionVO {
    private Long id;
    private String agentType;
    private String actionType;
    private String targetType;
    private Long targetId;
    private String reason;
    private String status;
    private LocalDateTime createTime;
}
