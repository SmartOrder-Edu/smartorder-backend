package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class FileFolderContentVO {
    private Long folderId;
    private String folderName;
//    private Long folderParentId;

    private List<FileFolderVO> folders;
    private List<FileResourceVO> files;
}
