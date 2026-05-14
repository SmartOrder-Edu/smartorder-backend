CREATE TABLE ai_short_term_memory (
                                      id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                      user_id BIGINT NOT NULL COMMENT '用户ID',
                                      subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID',
                                      session_id BIGINT DEFAULT NULL COMMENT '来源会话ID',

                                      memory_type VARCHAR(50) NOT NULL COMMENT '记忆类型：recent_goal近期目标、recent_problem近期问题、recent_preference近期偏好、unfinished_task未完成任务、dialogue_summary对话摘要',

                                      title VARCHAR(255) DEFAULT NULL COMMENT '记忆标题',
                                      content TEXT NOT NULL COMMENT '记忆内容',
                                      content_json JSON DEFAULT NULL COMMENT '结构化记忆内容',

                                      importance_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '重要性分数，0-100',
                                      confidence_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '置信度分数，0-100',
                                      last_used_time DATETIME DEFAULT NULL COMMENT '最近使用时间',

                                      expire_time DATETIME DEFAULT NULL COMMENT '过期时间',
                                      status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0失效，1有效',

                                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                      INDEX idx_user_id (user_id),
                                      INDEX idx_subject_id (subject_id),
                                      INDEX idx_memory_type (memory_type),
                                      INDEX idx_importance_score (importance_score),
                                      INDEX idx_expire_time (expire_time)
) COMMENT='短期记忆表';