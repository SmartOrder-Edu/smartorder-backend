package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class FileFolderContentVO {
    private Long id;
    private Long parentId;
    private String folderName;

    private List<FileFolderVO> childrenFolders;
    private List<FileResourceVO> childrenFiles;
}
