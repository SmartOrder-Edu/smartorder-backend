package com.smartorder.edu.student.domain.dto;

import lombok.Data;

/**
 * 旧版文件资源入参 DTO。
 *
 * @deprecated 全仓无任何引用。文件模块重构后，入参改用 {@code FileFolderDTO} /
 * {@code InitFileUploadDTO}，出参改用 {@link com.smartorder.edu.student.domain.vo.FileResourceVO}。
 * 新代码请使用上述类型，本类可在确认后删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Data
public class FileResourceDTO {
    private Long folderId;
    private String fileName;
    private String originalFileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer source;
    private String summaryContent;
    private Integer uploadStatus;
}
