CREATE TABLE student_profile_memory (
                                           id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                           user_id BIGINT NOT NULL COMMENT '用户ID',
                                           subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID',

                                           profile_version INT NOT NULL DEFAULT 1 COMMENT '画像版本号',

                                           basic_profile JSON DEFAULT NULL COMMENT '基础画像，例如专业、年级、学习目标、学习时间等',
                                           knowledge_profile JSON DEFAULT NULL COMMENT '知识基础画像，例如已掌握知识点、薄弱知识点',
                                           ability_profile JSON DEFAULT NULL COMMENT '能力画像，例如理解能力、推理能力、编程能力、表达能力',
                                           preference_profile JSON DEFAULT NULL COMMENT '学习偏好画像，例如喜欢视频、图文、案例、练习',
                                           cognitive_profile JSON DEFAULT NULL COMMENT '认知风格画像，例如偏理论、偏实践、偏图像化理解',
                                           weakness_profile JSON DEFAULT NULL COMMENT '薄弱点画像，例如知识点、错误类型、证据、置信度',
                                           behavior_profile JSON DEFAULT NULL COMMENT '行为画像，例如学习频率、活跃时间、资源使用习惯',
                                           emotion_profile JSON DEFAULT NULL COMMENT '学习状态画像，例如焦虑、拖延、自信程度等非敏感学习状态',

                                           profile_summary TEXT COMMENT '画像自然语言摘要',

                                           confidence_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '画像整体置信度，0-100',
                                           last_update_reason VARCHAR(255) DEFAULT NULL COMMENT '最近一次更新原因',

                                           created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                           updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                           UNIQUE KEY uk_user_subject (user_id, subject_id),
                                           INDEX idx_user_id (user_id),
                                           INDEX idx_subject_id (subject_id)
) COMMENT='学生画像记忆表';