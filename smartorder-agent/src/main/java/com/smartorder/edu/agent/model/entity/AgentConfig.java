package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AgentConfig {
    private Long id;
    private Long spaceId;
    private String agentName;
    private String agentAvatarUrl;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String systemPromptAddition;
    private String enabledTools;
    private String persona;
    private String customInstructions;
    private Integer isEnabled;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
