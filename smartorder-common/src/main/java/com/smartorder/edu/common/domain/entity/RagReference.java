package com.smartorder.edu.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * rag_references 实体类
 */
@Data
@TableName("rag_references")
public class RagReference {

    /**
     * RAG引用记录ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 关联的RAG检索日志ID
     */
    @TableField("query_log_id")
    private Long queryLogId;

    /**
     * 所属会话ID
     */
    @TableField("conversation_id")
    private Long conversationId;

    /**
     * 助手回答消息ID
     */
    @TableField("answer_message_id")
    private Long answerMessageId;

    /**
     * 被引用的知识库ID
     */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /**
     * 被引用的文件ID
     */
    @TableField("file_id")
    private Long fileId;

    /**
     * 被引用的文档ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 被引用的chunk ID，对应knowledge_chunks表
     */
    @TableField("chunk_id")
    private Long chunkId;

    /**
     * 被引用chunk在向量数据库中的vector ID
     */
    @TableField("vector_id")
    private String vectorId;

    /**
     * 向量检索相似度分数
     */
    @TableField("retrieval_score")
    private BigDecimal retrievalScore;

    /**
     * 重排序分数
     */
    @TableField("rerank_score")
    private BigDecimal rerankScore;

    /**
     * 最终引用排序，数值越小优先级越高
     */
    @TableField("final_rank")
    private Integer finalRank;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
