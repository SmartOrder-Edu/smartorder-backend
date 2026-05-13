package com.smartorder.edu.agent.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分阶段资源生成记录实体
 */
@Data
@NoArgsConstructor
public class AgentResourceLog {
    private Long id;
    private Long spaceId;
    private Long planId;
    private Integer phaseIndex;
    private String resourceType;
    private String title;
    private String status;
    private String resultUrl;
    private String generationParams;
    private Integer retryCount;
    private String errorMessage;
    private Long userId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
