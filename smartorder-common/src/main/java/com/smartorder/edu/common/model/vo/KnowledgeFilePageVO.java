package com.smartorder.edu.common.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeFilePageVO {

    /**
     * 文件ID
     */
    private Long id;

    /**
     * 文件名
     */
    private String originalName;

    /**
     * 访问地址
     */
    private String fileUrl;

    /**
     * 扩展名
     */
    private String fileExtension;

    /**
     * 类型（MIME类型）
     */
    private String mimeType;

    /**
     * 大小（字节）
     */
    private Long fileSize;

    /**
     * 解析状态
     */
    private String parseStatus;

    /**
     * 分块状态
     */
    private String chunkStatus;

    /**
     * 向量化状态
     */
    private String embeddingStatus;

    /**
     * 页数
     */
    private Integer pageCount;

    /**
     * 字数统计
     */
    private Integer wordCount;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 上传时间
     */
    private LocalDateTime createdAt;
}
