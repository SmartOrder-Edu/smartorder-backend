package com.smartorder.edu.manage.domain.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 智能体工具执行审计 VO
 */
@Data
public class AgentToolExecutionVO {
    private Long id;
    private String traceId;
    private String conversationId;
    private Long userId;
    private String userName; // 关联 User 表查出用户名
    private String toolName;
    private String arguments;
    private String result;
    private String status;
    private String errorMessage;
    private Integer durationMs;
    private LocalDateTime createTime;
}
