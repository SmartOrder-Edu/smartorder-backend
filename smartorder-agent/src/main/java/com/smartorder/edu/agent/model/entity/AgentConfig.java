package com.smartorder.edu.agent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 旧版 Agent 配置实体。
 *
 * @deprecated 全仓无任何引用，{@link com.smartorder.edu.agent.mapper.AgentConfigMapper}
 * 也没有调用方。Agent 的模型、温度、工具白名单等参数现由
 * {@code AgentConfiguration} 中的 {@code ChatModel} Bean 与配置项统一管理。
 * 本类可在确认后连同 Mapper 一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
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
