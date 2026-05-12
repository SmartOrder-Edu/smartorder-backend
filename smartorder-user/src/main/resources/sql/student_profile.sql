-- 学生画像表（Profile Agent 数据基础）
CREATE TABLE IF NOT EXISTS `student_profile` (
    `id`                              BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '档案ID',
    `user_id`                         BIGINT        NOT NULL                 COMMENT '用户ID（一对一）',
    `learning_style_preference`       JSON          DEFAULT NULL             COMMENT '学习风格偏好',
    `strengths`                       JSON          DEFAULT NULL             COMMENT '优势领域',
    `weaknesses`                      JSON          DEFAULT NULL             COMMENT '薄弱领域',
    `daily_available_minutes`         INT           DEFAULT 120              COMMENT '每日可用学习时间（分钟）',
    `preferred_time_slots`            JSON          DEFAULT NULL             COMMENT '偏好时间段',
    `concentration_duration_minutes`  INT           DEFAULT 45               COMMENT '单次专注时长（分钟）',
    `subjects_of_interest`            JSON          DEFAULT NULL             COMMENT '兴趣学科',
    `academic_level`                  VARCHAR(30)   DEFAULT NULL             COMMENT '学历',
    `major`                           VARCHAR(100)  DEFAULT NULL             COMMENT '专业',
    `semester`                        VARCHAR(20)   DEFAULT NULL             COMMENT '当前学期',
    `gpa`                             DECIMAL(3,2)  DEFAULT NULL             COMMENT 'GPA',
    `profile_version`                 INT           NOT NULL DEFAULT 1       COMMENT '档案版本号',
    `last_analyzed_at`                DATETIME      DEFAULT NULL             COMMENT '最近一次AI分析时间',
    `is_deleted`                      TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除：0=正常，1=已删除',
    `create_time`                     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`                     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生画像/档案表';
