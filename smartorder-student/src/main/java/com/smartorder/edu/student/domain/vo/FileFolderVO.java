package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileFolderVO {
    private Long id;
    private Long parentId; // 有无都行
    private String folderName;

    private Integer sort;
    private LocalDateTime updateTime;
}
