-- 评估答题记录表
CREATE TABLE IF NOT EXISTS `assessment_answer` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '答案ID',
    `attempt_id`          BIGINT        NOT NULL                 COMMENT '所属尝试ID',
    `question_id`         BIGINT        NOT NULL                 COMMENT '题目ID',
    `user_answer`         JSON          DEFAULT NULL             COMMENT '用户答案',
    `score`               DECIMAL(5,1)  DEFAULT NULL             COMMENT '本题得分',
    `is_correct`          TINYINT       DEFAULT NULL             COMMENT '是否正确：0=错误，1=正确，null=未评分',
    `ai_feedback`         TEXT          DEFAULT NULL             COMMENT 'AI逐题反馈/点评',
    `time_spent_seconds`  INT           DEFAULT NULL             COMMENT '本题耗时（秒）',
    `is_deleted`          TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_attempt_id` (`attempt_id`),
    KEY `idx_question_id` (`question_id`),
    UNIQUE KEY `uk_attempt_question` (`attempt_id`, `question_id`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估答题记录表';
