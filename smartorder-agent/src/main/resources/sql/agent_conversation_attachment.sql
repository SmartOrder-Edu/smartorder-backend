CREATE TABLE agent_conversation_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话附件ID',
    conversation_id VARCHAR(36) NOT NULL COMMENT 'Agent 会话ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    file_id BIGINT NOT NULL COMMENT '关联 knowledge_files.id',
    status VARCHAR(30) DEFAULT 'processing' COMMENT 'processing=处理中，ready=可检索，failed=失败，expired=过期，deleted=移除',
    expires_at DATETIME NULL COMMENT '临时附件过期时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_aca_conv_file (conversation_id, user_id, file_id),
    INDEX idx_aca_user_conv (user_id, conversation_id),
    INDEX idx_aca_file_id (file_id),
    INDEX idx_aca_status (status),
    INDEX idx_aca_expires_at (expires_at)
) COMMENT='Agent 会话临时附件绑定表';
