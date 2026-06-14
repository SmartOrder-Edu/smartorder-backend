package com.smartorder.edu.agent.model.dto;

import lombok.Data;

@Data
public class AttachmentUploadMergeRequest {
    private String conversationId;
    private String uploadId;
    private String fileHash;
    private String fileName;
    private Long fileSize;
    private Integer totalParts;
}
