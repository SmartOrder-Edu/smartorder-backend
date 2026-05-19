package com.smartorder.edu.student.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileFolderDTO {
    @NotNull(message = "父文件夹ID不能为空")
    private Long parentId;
    @NotNull(message = "文件夹名字不能为空")
    private String folderName;
}
