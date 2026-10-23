package com.smartorder.edu.manage.domain.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 后台分阶段资源生成日志展示 VO
 */
@Data
public class AgentResourceLogVO {
    private Long id;
    private Long spaceId;
    private Long planId;
    private Integer phaseIndex;
    private String resourceType; // PPT, DOC, QUIZ, EXAM, MINDMAP
    private String title;
    private String status;       // PENDING, GENERATING, COMPLETED, FAILED
    private String resultUrl;
    private String generationParams;
    private Integer retryCount;
    private String errorMessage;
    private Long userId;
    private String userName;     // 关联 User 表查出用户名
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
