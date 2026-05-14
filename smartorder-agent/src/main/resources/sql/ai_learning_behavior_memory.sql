CREATE TABLE ai_learning_behavior_memory (
                                             id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                             user_id BIGINT NOT NULL COMMENT '用户ID',
                                             subject_id BIGINT DEFAULT NULL COMMENT '学习科目ID',
                                             resource_id BIGINT DEFAULT NULL COMMENT '学习资源ID',

                                             behavior_type VARCHAR(50) NOT NULL COMMENT '行为类型：view_resource查看资源、finish_resource完成资源、do_exercise做题、wrong_answer错题、ask_question提问、like_resource点赞、dislike_resource点踩',

                                             behavior_content JSON DEFAULT NULL COMMENT '行为内容，例如题目ID、答案、学习时长、反馈文本等',

                                             knowledge_point VARCHAR(255) DEFAULT NULL COMMENT '关联知识点',
                                             score DECIMAL(5,2) DEFAULT NULL COMMENT '得分，例如练习得分',
                                             duration_seconds INT DEFAULT NULL COMMENT '学习时长，单位秒',

                                             memory_extract_status TINYINT NOT NULL DEFAULT 0 COMMENT '是否已用于记忆抽取：0未处理，1已处理',

                                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

                                             INDEX idx_user_id (user_id),
                                             INDEX idx_subject_id (subject_id),
                                             INDEX idx_resource_id (resource_id),
                                             INDEX idx_behavior_type (behavior_type),
                                             INDEX idx_knowledge_point (knowledge_point),
                                             INDEX idx_created_at (created_at)
) COMMENT='学习行为记忆表';