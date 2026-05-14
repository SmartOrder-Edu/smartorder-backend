CREATE TABLE ai_mid_term_memory (
                                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                    user_id BIGINT NOT NULL COMMENT '用户ID',
                                    subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID',

                                    memory_type VARCHAR(50) NOT NULL COMMENT '记忆类型：stage_summary阶段总结、stage_weakness阶段薄弱点、stage_goal阶段目标、learning_pattern学习模式、resource_feedback资源反馈',

                                    title VARCHAR(255) NOT NULL COMMENT '记忆标题',
                                    summary TEXT NOT NULL COMMENT '阶段性记忆摘要',
                                    evidence JSON DEFAULT NULL COMMENT '证据数据，例如对话ID、练习ID、错题ID、行为记录ID',

                                    start_time DATETIME DEFAULT NULL COMMENT '统计开始时间',
                                    end_time DATETIME DEFAULT NULL COMMENT '统计结束时间',

                                    importance_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '重要性分数，0-100',
                                    confidence_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '置信度分数，0-100',

                                    vector_id VARCHAR(100) DEFAULT NULL COMMENT '向量数据库中的向量ID',
                                    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0失效，1有效',

                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                    INDEX idx_user_id (user_id),
                                    INDEX idx_subject_id (subject_id),
                                    INDEX idx_memory_type (memory_type),
                                    INDEX idx_time_range (start_time, end_time),
                                    INDEX idx_vector_id (vector_id)
) COMMENT='中期记忆表';