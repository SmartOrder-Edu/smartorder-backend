CREATE TABLE `student_profile`
(
    `id`                       bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '学生画像主键ID',
    `user_id`                  bigint unsigned NOT NULL COMMENT '学生用户ID',
    `profile_version`          int unsigned NOT NULL DEFAULT '1' COMMENT '整份学生画像版本号，每次有效更新后递增',
    `learning_background`      json                   DEFAULT NULL COMMENT '学习背景：学历、年级、专业、已修课程、基础能力及学习环境',
    `learning_goals`           json                   DEFAULT NULL COMMENT '全局学习目标：长期目标、阶段目标、优先级、截止时间及时间预算',
    `ability_profile`          json                   DEFAULT NULL COMMENT '综合能力：概念理解、问题分析、算法设计、代码实现、调试及知识迁移能力',
    `common_misconceptions`    json                   DEFAULT NULL COMMENT '跨科目共性易错模式：重复出现的错误习惯、可能原因、影响范围及干预建议',
    `learning_preference`      json                   DEFAULT NULL COMMENT '稳定学习偏好：资源形式、讲解方式、内容粒度、练习和反馈方式',
    `metacognitive_profile`    json                   DEFAULT NULL COMMENT '元认知画像：学习规划、自我评估、反思、主动求助及信心校准能力',
    `subject_profiles`         json                   DEFAULT NULL COMMENT '各科目画像集合：包含科目目标、知识掌握、易错点、科目偏好、学习行为和当前状态',
    `dimension_metadata`       json                   DEFAULT NULL COMMENT '各全局画像维度的数据来源、证据数量、置信度、更新时间及有效期限',
    `current_learning_state`   json                   DEFAULT NULL COMMENT '当前学习状态',
    `profile_summary`          text COMMENT '根据结构化画像生成的自然语言摘要，仅作为大模型上下文缓存',
    `profile_confidence_score` decimal(5, 2) NOT NULL DEFAULT '0.00' COMMENT '整份学生画像可信度，范围0到100',
    `last_update_reason`       varchar(500)           DEFAULT NULL COMMENT '最近一次画像更新原因',
    `last_assessed_at`         datetime               DEFAULT NULL COMMENT '最近一次系统性重新评估画像的时间',
    `created_at`               datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '画像创建时间',
    `updated_at`               datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '画像最近更新时间',
    `is_deleted`               int                    DEFAULT NULL COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_profile_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2066499184551948290 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学生动态学习画像表';