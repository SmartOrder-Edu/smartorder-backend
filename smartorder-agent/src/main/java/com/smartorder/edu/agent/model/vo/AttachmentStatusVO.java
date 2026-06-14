package com.smartorder.edu.agent.model.vo;

import lombok.Data;

@Data
public class AttachmentStatusVO {
    private String conversationId;
    private Long fileId;
    private Long jobId;
    private String fileName;
    private Long fileSize;
    private String fileExtension;
    private String status;
    private String parseStatus;
    private String chunkStatus;
    private String embeddingStatus;
    private Integer progress;
    private String currentStep;
    private String errorMessage;
}
