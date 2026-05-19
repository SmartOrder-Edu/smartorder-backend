package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class FileFolderVO {
    private Long folderId;
    private Long folderParentId; // 有无都行
    private String folderName;

    private Integer sort;
    private LocalDateTime updateTime;
    private List<FileFolderVO> children;
}
