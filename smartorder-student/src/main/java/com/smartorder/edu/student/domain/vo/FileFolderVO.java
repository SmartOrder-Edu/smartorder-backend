package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class FileFolderVO {
    private Long id;
    private Long parentId;
    private String folderName;
    private Long userId;
    private LocalDateTime createdTime;
    private LocalDateTime updateTime;
    private List<FileFolderVO> children;
}
