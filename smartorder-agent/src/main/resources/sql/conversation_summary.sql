-- 会话摘要表
CREATE TABLE IF NOT EXISTS `conversation_summary` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `conversation_id`  VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `user_id`          BIGINT        NOT NULL                 COMMENT '用户ID',
    `title`            VARCHAR(200)  DEFAULT NULL             COMMENT '会话标题',
    `summary_text`     TEXT          NOT NULL                 COMMENT '会话摘要',
    `message_count`    INT           DEFAULT 0                COMMENT '摘要覆盖消息数量',
    `is_deleted`       TINYINT       DEFAULT 0                COMMENT '逻辑删除：0未删除，1已删除',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv` (`conversation_id`),
    INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='长会话摘要表';
