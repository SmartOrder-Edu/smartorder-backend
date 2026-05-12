CREATE TABLE rag_query_logs (
      id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'RAG检索日志ID',

      user_id BIGINT NOT NULL COMMENT '所属用户ID',
      conversation_id BIGINT COMMENT '所属会话ID',
      message_id BIGINT COMMENT '用户提问消息ID',

      query_text TEXT NOT NULL COMMENT '用户原始问题',
      rewritten_query TEXT COMMENT '系统改写后的检索问题，如果有query rewrite则记录',

      knowledge_base_ids JSON COMMENT '本次检索使用的知识库ID列表',

      top_k INT COMMENT '本次检索设置的召回数量',
      score_threshold DECIMAL(6,4) COMMENT '本次检索设置的相似度阈值',

      retrieved_count INT COMMENT '实际召回的chunk数量',
      rerank_enabled BOOLEAN DEFAULT FALSE COMMENT '是否启用了rerank重排序',

      latency_ms INT COMMENT '本次RAG检索耗时，单位毫秒',

      created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

      INDEX idx_rql_user_id (user_id),
      INDEX idx_rql_conversation_id (conversation_id)
) COMMENT='RAG检索日志表，用于记录每次知识库检索行为和效果';