-- 评估尝试记录表
CREATE TABLE IF NOT EXISTS `assessment_attempt` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '尝试ID',
    `user_id`             BIGINT       NOT NULL                 COMMENT '用户ID',
    `assessment_id`       BIGINT       NOT NULL                 COMMENT '评估ID',
    `attempt_number`      INT          NOT NULL DEFAULT 1       COMMENT '第几次尝试',
    `start_time`          DATETIME     DEFAULT NULL             COMMENT '开始时间',
    `end_time`            DATETIME     DEFAULT NULL             COMMENT '结束时间',
    `time_spent_seconds`  INT          DEFAULT NULL             COMMENT '耗时（秒）',
    `total_score`         DECIMAL(6,1) DEFAULT NULL             COMMENT '得分',
    `max_score`           DECIMAL(6,1) DEFAULT NULL             COMMENT '满分（冗余）',
    `status`              VARCHAR(20)  NOT NULL DEFAULT 'in_progress' COMMENT '状态：in_progress/completed/graded',
    `graded_by`           VARCHAR(10)  DEFAULT NULL             COMMENT '评分方式：ai/manual',
    `ai_feedback`         TEXT         DEFAULT NULL             COMMENT 'AI总体反馈/评估报告',
    `is_deleted`          TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_assessment_id` (`assessment_id`),
    KEY `idx_user_assessment` (`user_id`, `assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估尝试记录表';
