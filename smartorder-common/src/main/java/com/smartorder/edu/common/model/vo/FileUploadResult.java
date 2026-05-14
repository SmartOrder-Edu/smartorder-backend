package com.smartorder.edu.common.model.vo;

import lombok.Data;

@Data
public class FileUploadResult {
    private String fileUrl;       // 文件的访问链接
    private String originalName;  // 原始文件名
    private Long size;            // 文件大小（字节）
    private String contentType;   // 文件 MIME 类型
    private String msgType;       // 对应的消息类型（IMAGE, FILE 等）
}