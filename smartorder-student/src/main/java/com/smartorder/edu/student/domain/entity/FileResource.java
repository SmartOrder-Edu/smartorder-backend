package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件资源表
 */
@Data
@NoArgsConstructor
public class FileResource {

    /** 文件资源ID */
    private Long id;

    /** 所属文件夹ID（0=根目录） */
    private Long folderId;

    /** 上传用户ID */
    private Long userId;

    /** 文件名称--（可能被重命名）  */
    private String fileName;

    /** 原始文件名*/
    private String originalFileName;

    /** OSS文件URL */
    private String fileUrl;

    /** 文件类型（pdf/docx/ppt/mp4/jpg/png等） */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 来源：1-本地上传，2-AI生成归档，3-广场转存 */
    private Integer source;

    /** AI解析后的摘要内容 */
    private String summarycontent;

    /** 上传状态：0-上传中，1-上传成功，2-上传失败 */
    private String uploadStatus;

    /** 解析状态：0-未解析，1-解析中，2-向量化完成，3-解析失败 */
    private Integer parseStatus;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
