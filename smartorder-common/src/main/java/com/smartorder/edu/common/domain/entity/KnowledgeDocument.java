package com.smartorder.edu.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_documents 实体类
 */
@Data
@TableName("knowledge_documents")
public class KnowledgeDocument {

    /**
     * 文档ID，系统内部唯一标识
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
     * 来源文件ID，表示该文档由哪个文件解析得到
     */
    @TableField("file_id")
    private Long fileId;

    /**
     * 文档标题，通常来自文件名、PDF标题或用户手动填写
     */
    @TableField("title")
    private String title;

    /**
     * 来源类型：file=文件上传，url=网页导入，manual=手动创建，note=笔记
     */
    @TableField("source_type")
    private String sourceType;

    /**
     * 解析后完整文本的存储路径，适合将大文本存到对象存储
     */
    @TableField("raw_text_path")
    private String rawTextPath;

    /**
     * 文档摘要，可由AI生成，用于前端预览
     */
    @TableField("summary")
    private String summary;

    /**
     * 文档语言，例如：zh-CN、en-US
     */
    @TableField("language")
    private String language;

    /**
     * 文档页数
     */
    @TableField("page_count")
    private Integer pageCount;

    /**
     * 文档字数
     */
    @TableField("word_count")
    private Integer wordCount;

    /**
     * 文档切分后的chunk数量
     */
    @TableField("chunk_count")
    private Integer chunkCount;

    /**
     * 文档元信息，例如作者、页数、章节目录、解析器版本、原始格式信息
     */
    @TableField("metadata")
    private String metadata;

    /**
     * 文档版本号，文件重新解析或更新时递增
     */
    @TableField("version")
    private Integer version;

    /**
     * 文档状态：active=正常，deleted=删除
     */
    @TableField("status")
    private String status;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
