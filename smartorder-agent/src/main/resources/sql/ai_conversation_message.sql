CREATE TABLE ai_conversation_message (
                                         id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                         session_id BIGINT NOT NULL COMMENT '会话ID',
                                         user_id BIGINT NOT NULL COMMENT '用户ID',
                                         agent_id BIGINT DEFAULT NULL COMMENT '回复该消息的Agent ID',

                                         role VARCHAR(30) NOT NULL COMMENT '消息角色：user用户、assistant助手、system系统、agent智能体、tool工具',
                                         content LONGTEXT NOT NULL COMMENT '消息内容',

                                         content_type VARCHAR(50) NOT NULL DEFAULT 'text' COMMENT '内容类型：text文本、markdown、image、audio、video、file、tool_result',
                                         message_order INT NOT NULL COMMENT '消息顺序',

                                         parent_message_id BIGINT DEFAULT NULL COMMENT '父消息ID，用于多轮上下文追踪',
                                         tool_name VARCHAR(100) DEFAULT NULL COMMENT '调用的工具名称',
                                         tool_input JSON DEFAULT NULL COMMENT '工具输入参数',
                                         tool_output JSON DEFAULT NULL COMMENT '工具输出结果',

                                         token_count INT DEFAULT 0 COMMENT 'Token数量',
                                         is_memory_candidate TINYINT NOT NULL DEFAULT 0 COMMENT '是否是记忆候选内容：0否，1是',
                                         memory_extract_status TINYINT NOT NULL DEFAULT 0 COMMENT '记忆抽取状态：0未抽取，1已抽取，2无需抽取',

                                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                         deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',

                                         INDEX idx_session_id (session_id),
                                         INDEX idx_user_id (user_id),
                                         INDEX idx_agent_id (agent_id),
                                         INDEX idx_role (role),
                                         INDEX idx_created_at (created_at)
) COMMENT='历史对话消息表';