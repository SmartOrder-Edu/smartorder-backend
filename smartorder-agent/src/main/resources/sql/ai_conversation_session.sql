CREATE TABLE ai_conversation_session (
                                         id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                         user_id BIGINT NOT NULL COMMENT '用户ID',
                                         subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID，例如高数、英语、操作系统',
                                         agent_id BIGINT DEFAULT NULL COMMENT '主导本次会话的Agent ID',

                                         session_title VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
                                         session_type VARCHAR(50) NOT NULL DEFAULT 'learning' COMMENT '会话类型：learning学习、qa答疑、planning规划、review复盘、resource_generation资源生成',

                                         start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '会话开始时间',
                                         end_time DATETIME DEFAULT NULL COMMENT '会话结束时间',

                                         summary TEXT COMMENT '本次会话摘要',
                                         memory_status TINYINT NOT NULL DEFAULT 0 COMMENT '记忆处理状态：0未处理，1已摘要，2已抽取画像，3已入向量库',

                                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                         deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',

                                         INDEX idx_user_id (user_id),
                                         INDEX idx_subject_id (subject_id),
                                         INDEX idx_session_type (session_type),
                                         INDEX idx_start_time (start_time)
) COMMENT='历史对话会话表';