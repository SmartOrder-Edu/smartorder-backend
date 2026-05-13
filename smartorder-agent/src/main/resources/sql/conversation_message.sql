-- 会话消息持久化表
CREATE TABLE IF NOT EXISTS `conversation_message` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `conversation_id`  VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `user_id`          BIGINT        NOT NULL                 COMMENT '用户ID',
    `role`             VARCHAR(20)   NOT NULL                 COMMENT '消息角色：user、assistant、system、tool',
    `content`          MEDIUMTEXT    DEFAULT NULL             COMMENT '消息内容',
    `tool_name`        VARCHAR(100)  DEFAULT NULL             COMMENT '工具名称，如果是工具消息则记录',
    `metadata_json`    JSON          DEFAULT NULL             COMMENT '元数据，例如模型名称、token数量、工具参数等',
    `is_compressed`    TINYINT       DEFAULT 0                COMMENT '是否已被摘要压缩：0否，1是',
    `is_deleted`       TINYINT       DEFAULT 0                COMMENT '逻辑删除：0未删除，1已删除',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_conv` (`conversation_id`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_conv_time` (`conversation_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息持久化表';
