CREATE TABLE ai_long_term_memory (
                                     id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                     user_id BIGINT NOT NULL COMMENT '用户ID',
                                     subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID',

                                     memory_type VARCHAR(50) NOT NULL COMMENT '记忆类型：profile画像、stable_preference稳定偏好、long_weakness长期薄弱点、ability_model能力模型、learning_style学习风格、goal长期目标',

                                     title VARCHAR(255) NOT NULL COMMENT '记忆标题',
                                     content TEXT NOT NULL COMMENT '长期记忆内容',
                                     content_json JSON DEFAULT NULL COMMENT '结构化长期记忆内容',

                                     source_type VARCHAR(50) NOT NULL DEFAULT 'conversation' COMMENT '来源类型：conversation对话、behavior行为、exercise练习、agent_summary智能体总结、manual人工录入',
                                     source_ids JSON DEFAULT NULL COMMENT '来源ID集合，例如消息ID、会话ID、练习ID',

                                     importance_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '重要性分数，0-100',
                                     confidence_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '置信度分数，0-100',
                                     stability_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '稳定性分数，越高表示越适合长期保存',

                                     vector_id VARCHAR(100) DEFAULT NULL COMMENT '向量数据库中的向量ID',
                                     last_used_time DATETIME DEFAULT NULL COMMENT '最近使用时间',

                                     status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0失效，1有效，2待验证，3冲突',
                                     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                     INDEX idx_user_id (user_id),
                                     INDEX idx_subject_id (subject_id),
                                     INDEX idx_memory_type (memory_type),
                                     INDEX idx_importance_score (importance_score),
                                     INDEX idx_confidence_score (confidence_score),
                                     INDEX idx_vector_id (vector_id)
) COMMENT='长期记忆表';