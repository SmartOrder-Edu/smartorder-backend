package com.smartorder.edu.agent.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class AttachmentUploadResponse {
    private Boolean isFinished;
    private String uploadId;
    private List<Integer> uploadedParts;
    private String conversationId;
    private Long fileId;
    private Long jobId;
    private String fileName;
    private String fileUrl;
    private String status;
    private String parseStatus;
    private String chunkStatus;
    private String embeddingStatus;
    private Integer progress;
    private String currentStep;
    private String errorMessage;
}
