package com.smartorder.edu.manage.domain.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库解析异步任务 VO
 */
@Data
public class KnowledgeFileJobVO {
    private Long id;
    private Long userId;
    private String userName;        // 用户名
    private Long knowledgeBaseId;
    private String knowledgeBaseName; // 知识库名
    private Long fileId;
    private String fileName;        // 文件名
    private String jobType;         // parse, chunk, embed 等
    private String status;          // pending, running, success, failed
    private Integer progress;       // 0 - 100
    private String currentStep;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
