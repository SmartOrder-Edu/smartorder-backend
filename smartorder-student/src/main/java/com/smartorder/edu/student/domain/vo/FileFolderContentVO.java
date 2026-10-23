package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class FileFolderContentVO {
    private Long folderId;
    private String folderName;
    /**
     * 当前用户已用空间（字节）：该用户所有文件夹内文件大小之和，取自配额表预聚合值
     */
    private Long userSpace;
//    private Long folderParentId;

    private List<FileFolderVO> folders;
    private List<FileResourceVO> files;
}
