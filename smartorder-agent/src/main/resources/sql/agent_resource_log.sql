-- 分阶段资源生成记录表
CREATE TABLE IF NOT EXISTS `agent_resource_log` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `space_id`         BIGINT        NOT NULL                 COMMENT '学习空间ID',
    `plan_id`          BIGINT        DEFAULT NULL             COMMENT '学习规划ID',
    `phase_index`      INT           DEFAULT NULL             COMMENT '学习阶段序号',
    `resource_type`    VARCHAR(50)   DEFAULT NULL             COMMENT '资源类型：PPT、DOC、QUIZ、EXAM、MINDMAP',
    `title`            VARCHAR(200)  DEFAULT NULL             COMMENT '资源标题',
    `status`           VARCHAR(20)   DEFAULT 'PENDING'        COMMENT '状态：PENDING、GENERATING、COMPLETED、FAILED',
    `result_url`       VARCHAR(500)  DEFAULT NULL             COMMENT '结果地址',
    `generation_params` JSON         DEFAULT NULL             COMMENT '生成参数',
    `retry_count`      INT           DEFAULT 0                COMMENT '重试次数',
    `error_message`    TEXT          DEFAULT NULL             COMMENT '失败原因',
    `user_id`          BIGINT        NOT NULL                 COMMENT '用户ID',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_space_phase` (`space_id`, `phase_index`),
    INDEX `idx_status` (`status`),
    INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分阶段资源生成记录表';
