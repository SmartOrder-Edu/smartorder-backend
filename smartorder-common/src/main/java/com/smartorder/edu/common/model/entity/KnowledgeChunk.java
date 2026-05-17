package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_chunks 实体类
 */
@Data
@TableName("knowledge_chunks")
public class KnowledgeChunk {

    /**
     * chunk ID，系统内部
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID，用于多用户数据隔离
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 所属知识库ID，用于限定RAG检索范围
     */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /**
     * 来源文件ID，用于追溯chunk来自哪个文件
     */
    @TableField("file_id")
    private Long fileId;

    /**
     * 来源文档ID，用于追溯chunk来自
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * chunk在当前文档中的顺序编号
     */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /**
     * chunk类型：text=普通文本，table=表格，image_ocr=图片OCR，code=代码
     */
    @TableField("chunk_type")
    private String chunkType;

    /**
     * chunk内容预览，只存前几百字，用于前端展示和调试，不存完整文本
     */
    @TableField("content_preview")
    private String contentPreview;

    /**
     * chunk内容哈希，用于去重和判断是否需要重新生成
     */
    @TableField("content_hash")
    private String contentHash;

    /**
     * chunk token数量，用于控制上下文长度
     */
    @TableField("token_count")
    private Integer tokenCount;

    /**
     * chunk字符数量
     */
    @TableField("char_count")
    private Integer charCount;

    /**
     * chunk所在页码，用于PDF、PPT、Word引用展示
     */
    @TableField("page_number")
    private Integer pageNumber;

    /**
     * chunk所属章节标题
     */
    @TableField("section_title")
    private String sectionTitle;

    /**
     * 章节层级，例如1=一级标题，2=二级标题
     */
    @TableField("section_level")
    private Integer sectionLevel;

    /**
     * 标题路径，例如：第一章/进程管理/
     */
    @TableField("title_path")
    private String titlePath;

    /**
     * 上一个chunk ID，用于上下文扩展
     */
    @TableField("prev_chunk_id")
    private Long prevChunkId;

    /**
     * 下一个chunk ID，用于上下文串联
     */
    @TableField("next_chunk_id")
    private Long nextChunkId;

    /**
     * 向量数据库collection名称
     */
    @TableField("vector_collection")
    private String vectorCollection;

    /**
     * 向量数据库中的向量ID，用于从向量库取完整chunk和embedding
     */
    @TableField("vector_id")
    private String vectorId;

    /**
     * 该chunk使用的向量模型名称
     */
    @TableField("embedding_model")
    private String embeddingModel;

    /**
     * 向量化状态：pending=待向量化，embedding=向量化中，embedded=已向量化，failed=向量化失败
     */
    @TableField("embedding_status")
    private String embeddingStatus;

    /**
     * 向量化失败错误信息
     */
    @TableField("embedding_error")
    private String embeddingError;

    /**
     * chunk元信息，例如表格结构、图片位置、页面坐标、标题信息
     */
    @TableField("metadata")
    private String metadata;

    /**
     * chunk状态：active=正常，deleted=已删除
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



    private String content;

}
