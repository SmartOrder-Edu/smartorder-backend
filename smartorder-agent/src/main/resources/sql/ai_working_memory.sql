CREATE TABLE ai_working_memory (
                                   id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                   user_id BIGINT NOT NULL COMMENT '用户ID',
                                   session_id BIGINT NOT NULL COMMENT '会话ID',
                                   agent_id BIGINT DEFAULT NULL COMMENT 'Agent ID',

                                   task_id BIGINT DEFAULT NULL COMMENT '当前任务ID',
                                   memory_key VARCHAR(100) NOT NULL COMMENT '工作记忆键名',
                                   memory_value JSON NOT NULL COMMENT '工作记忆内容，使用JSON存储当前上下文、任务目标、临时变量等',

                                   expire_time DATETIME DEFAULT NULL COMMENT '过期时间',
                                   status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0失效，1有效',

                                   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                   INDEX idx_user_session (user_id, session_id),
                                   INDEX idx_agent_id (agent_id),
                                   INDEX idx_task_id (task_id),
                                   INDEX idx_expire_time (expire_time)
) COMMENT='工作记忆表';