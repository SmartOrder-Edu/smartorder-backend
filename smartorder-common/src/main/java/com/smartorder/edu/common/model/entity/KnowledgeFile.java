package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_files 实体类
 */
@Data
@TableName("knowledge_files")
public class KnowledgeFile {

    /**
     * 文件ID，系统内部唯一标识
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 所属知识库ID
     */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /**
     * 用户上传时的原始文件名
     */
    @TableField("original_name")
    private String originalName;

    /**
     * 系统存储时生成的文件名，用于避免文件重名
     */
    @TableField("stored_name")
    private String storedName;

    /**
     * 文件访问地址，用于前端预览或下载
     */
    @TableField("file_url")
    private String fileUrl;

    /**
     * 文件扩展名，例如：pdf、docx、pptx、txt、md等
     */
    @TableField("file_extension")
    private String fileExtension;

    /**
     * 文件MIME类型，例如：application/pdf
     */
    @TableField("mime_type")
    private String mimeType;

    /**
     * 文件大小，单位为字节
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * 文件哈希值，用于去重和判断文件是否变化
     */
    @TableField("file_hash")
    private String fileHash;

    /**
     * 解析状态：pending=待解析，parsing=解析中，parsed=解析完成，failed=解析失败
     */
    @TableField("parse_status")
    private String parseStatus;

    /**
     * 解析失败原因
     */
    @TableField("parse_error")
    private String parseError;

    /**
     * 分块状态：pending=待分块，chunking=分块中，chunked=分块完成，failed=分块失败
     */
    @TableField("chunk_status")
    private String chunkStatus;

    /**
     * 分块失败原因
     */
    @TableField("chunk_error")
    private String chunkError;

    /**
     * 向量化状态：pending=待向量化，embedding=向量化中，embedded=向量化完成，failed=向量化失败
     */
    @TableField("embedding_status")
    private String embeddingStatus;

    /**
     * 向量化失败原因
     */
    @TableField("embedding_error")
    private String embeddingError;

    /**
     * 文件页数，主要用于PDF、PPT、Word等文档
     */
    @TableField("page_count")
    private Integer pageCount;

    /**
     * 文件字数统计
     */
    @TableField("word_count")
    private Integer wordCount;

    /**
     * 该文件切分出的chunk数量
     */
    @TableField("chunk_count")
    private Integer chunkCount;

    /**
     * 文件状态：active=正常，deleted=删除
     */
    @TableField("status")
    private String status;

    /**
     * 上传时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
