package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_bases 实体类
 */
@Data
@TableName("knowledge_bases")
public class KnowledgeBase {

    /**
     * 知识库ID，系统内部唯一标识
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID，表示该知识库属于哪个用户
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 知识库名称，例如：操作系统知识库、英语四级知识库
     */
    @TableField("name")
    private String name;

    /**
     * 知识库描述，用于说明该知识库的内容范围和用途
     */
    @TableField("description")
    private String description;

    /**
     * 知识库分类，例如：课程学习、项目资料、论文阅读、个人笔记
     */
    @TableField("category")
    private String category;

    /**
     * 知识库封面图地址，用于前端展示
     */
    @TableField("cover_url")
    private String coverUrl;


    /**
     * 该知识库默认使用的向量模型名称，例如：bge-m3、text-embedding-3-small
     */
    @TableField("embedding_model")
    private String embeddingModel;

    /**
     * 默认分块策略：recursive=递归分块，markdown=按标题分块，sentence=按句子分块
     */
    @TableField("chunk_strategy")
    private String chunkStrategy;

    /**
     * 默认召回数量，表示RAG检索时默认返回多少个chunk
     */
    @TableField("retrieval_top_k")
    private Integer retrievalTopK;

    /**
     * 默认相似度阈值，低于该分数的chunk不参与回答
     */
    @TableField("score_threshold")
    private BigDecimal scoreThreshold;

    /**
     * 知识库下文件数量，冗余字段，用于前端快速展示
     */
    @TableField("file_count")
    private Integer fileCount;

    /**
     * 知识库下文档数量，冗余字段，用于前端快速展示
     */
    @TableField("document_count")
    private Integer documentCount;

    /**
     * 知识库下chunk数量，冗余字段，用于前端快速展示
     */
    @TableField("chunk_count")
    private Integer chunkCount;

    /**
     * 知识库下所有文件总大小，单位为字节
     */
    @TableField("total_size")
    private Long totalSize;

    /**
     * 知识库最近一次被检索或使用的时间
     */
    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;

    /**
     * 知识库内容最近更新时间，例如新增文件、删除文件、重新解析
     */
    @TableField("last_updated_at")
    private LocalDateTime lastUpdatedAt;

    /**
     * 知识库状态：active=正常，archived=归档，deleted=删除
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
