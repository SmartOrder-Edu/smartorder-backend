package com.smartorder.edu.student.domain.dto;

import lombok.Data;

@Data
public class FileResourceDTO {
    private Long folderId;
    private String fileName;
    private String originalFileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer source;
    private String summarycontent;
    private String uploadStatus;
}
