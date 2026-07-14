-- 学习资源生成任务表：用于跟踪资料包/单资源的异步生成状态
CREATE TABLE IF NOT EXISTS `resource_generation_job` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '生成任务ID',
    `user_id`         BIGINT        NOT NULL                COMMENT '用户ID',
    `space_id`        BIGINT        NOT NULL                COMMENT '学习空间ID',
    `resource_id`     BIGINT        NOT NULL                COMMENT '资源ID',
    `package_id`      VARCHAR(64)   DEFAULT NULL            COMMENT '资料包ID',
    `resource_type`   VARCHAR(30)   NOT NULL                COMMENT '资源类型',
    `title`           VARCHAR(200)  NOT NULL                COMMENT '任务标题',
    `reason`          VARCHAR(1000) DEFAULT NULL            COMMENT '生成原因',
    `status`          VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT '状态：pending/generating/generated/failed',
    `retry_count`     INT           NOT NULL DEFAULT 0      COMMENT '重试次数',
    `error_message`   TEXT          DEFAULT NULL            COMMENT '失败原因',
    `trace_id`        VARCHAR(64)   DEFAULT NULL            COMMENT '追踪ID',
    `is_deleted`      TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_space_status` (`space_id`, `status`),
    KEY `idx_resource_id` (`resource_id`),
    KEY `idx_package_id` (`package_id`),
    KEY `idx_user_space` (`user_id`, `space_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习资源生成任务表';
