package com.smartorder.edu.agent.model.dto;

import lombok.Data;

@Data
public class AttachmentUploadInitRequest {
    private String conversationId;
    private String fileHash;
    private String fileName;
    private Long fileSize;
}
