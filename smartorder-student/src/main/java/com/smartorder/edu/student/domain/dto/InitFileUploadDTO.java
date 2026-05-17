package com.smartorder.edu.student.domain.dto;

import lombok.Data;

@Data
public class InitFileUploadDTO {
    private String fileName;
    private Long fileSize;
    private String fileHash;
    private Long targetFolderId;
}
