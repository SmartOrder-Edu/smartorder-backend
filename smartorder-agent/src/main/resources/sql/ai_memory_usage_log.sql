CREATE TABLE ai_memory_usage_log (
                                     id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                     user_id BIGINT NOT NULL COMMENT '用户ID',
                                     session_id BIGINT DEFAULT NULL COMMENT '会话ID',
                                     agent_id BIGINT DEFAULT NULL COMMENT 'Agent ID',

                                     memory_layer VARCHAR(50) NOT NULL COMMENT '记忆层级：working、short_term、mid_term、long_term、profile',
                                     memory_id BIGINT NOT NULL COMMENT '记忆ID',

                                     usage_scene VARCHAR(100) NOT NULL COMMENT '使用场景：qa答疑、planning规划、recommend推荐、resource_generation资源生成、evaluation评估',
                                     usage_reason TEXT DEFAULT NULL COMMENT '使用原因',

                                     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

                                     INDEX idx_user_id (user_id),
                                     INDEX idx_session_id (session_id),
                                     INDEX idx_agent_id (agent_id),
                                     INDEX idx_memory_layer (memory_layer),
                                     INDEX idx_memory_id (memory_id),
                                     INDEX idx_usage_scene (usage_scene)
) COMMENT='记忆使用日志表';