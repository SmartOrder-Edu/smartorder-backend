package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class FileResource {
    private Long id;
    private Long folderId;
    private Long userId;
    private String fileName;
    private String originalFileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer source;
    private String summarycontent;
    private String uploadStatus;
    private Integer parseStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
