package com.smartorder.edu.common.model.vo;

import lombok.Data;

/**
 * 旧版文件上传结果 VO。
 *
 * @deprecated 全仓无任何引用。当前上传接口按业务各自返回：网盘/文件模块返回
 * {@code Result<String>} 或 {@code Result<Map<String, Object>>}（见 FileFolderController），
 * 附件模块返回 {@code AttachmentUploadResponse}。新代码请沿用对应模块的返回结构，
 * 本类可在确认后删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Data
public class FileUploadResult {
    private String fileUrl;       // 文件的访问链接
    private String originalName;  // 原始文件名
    private Long size;            // 文件大小（字节）
    private String contentType;   // 文件 MIME 类型
    private String msgType;       // 对应的消息类型（IMAGE, FILE 等）
}