package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * rag_query_logs 实体类
 */
@Data
@TableName("rag_query_logs")
public class RagQueryLog {

    /**
     * RAG检索日志ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 所属会话ID
     */
    @TableField("conversation_id")
    private Long conversationId;

    /**
     * 用户提问消息ID
     */
    @TableField("message_id")
    private Long messageId;

    /**
     * 用户原始问题
     */
    @TableField("query_text")
    private String queryText;

    /**
     * 系统改写后的检索问题，如果有query rewrite则记录
     */
    @TableField("rewritten_query")
    private String rewrittenQuery;

    /**
     * 本次检索使用的知识库ID列表
     */
    @TableField("knowledge_base_ids")
    private String knowledgeBaseIds;

    /**
     * 本次检索设置的召回数量
     */
    @TableField("top_k")
    private Integer topK;

    /**
     * 本次检索设置的相似度阈值
     */
    @TableField("score_threshold")
    private BigDecimal scoreThreshold;

    /**
     * 实际召回的chunk数量
     */
    @TableField("retrieved_count")
    private Integer retrievedCount;

    /**
     * 是否启用了rerank重排序
     */
    @TableField("rerank_enabled")
    private Boolean rerankEnabled;

    /**
     * 本次RAG检索耗时，单位毫秒
     */
    @TableField("latency_ms")
    private Integer latencyMs;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
