CREATE TABLE ai_memory_conflict (
                                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                    user_id BIGINT NOT NULL COMMENT '用户ID',
                                    subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID',

                                    old_memory_id BIGINT NOT NULL COMMENT '旧记忆ID',
                                    new_memory_id BIGINT NOT NULL COMMENT '新记忆ID',

                                    conflict_type VARCHAR(50) NOT NULL COMMENT '冲突类型：preference_conflict偏好冲突、goal_conflict目标冲突、ability_conflict能力判断冲突、weakness_conflict薄弱点冲突',
                                    conflict_description TEXT NOT NULL COMMENT '冲突描述',

                                    resolve_status TINYINT NOT NULL DEFAULT 0 COMMENT '解决状态：0未解决，1采用新记忆，2保留旧记忆，3合并，4人工确认',
                                    resolve_reason TEXT DEFAULT NULL COMMENT '解决原因',

                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    resolved_at DATETIME DEFAULT NULL COMMENT '解决时间',

                                    INDEX idx_user_id (user_id),
                                    INDEX idx_subject_id (subject_id),
                                    INDEX idx_old_memory_id (old_memory_id),
                                    INDEX idx_new_memory_id (new_memory_id),
                                    INDEX idx_resolve_status (resolve_status)
) COMMENT='记忆冲突表';