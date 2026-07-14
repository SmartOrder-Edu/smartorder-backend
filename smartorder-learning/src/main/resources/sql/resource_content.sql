-- 学习资源内容表：resource 保存资源元信息，本表保存可预览/可学习的实际内容
CREATE TABLE IF NOT EXISTS `resource_content` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '资源内容ID',
    `resource_id`     BIGINT       NOT NULL                COMMENT '资源ID',
    `content_type`    VARCHAR(30)  NOT NULL                COMMENT '内容类型：markdown/html/json/external_link',
    `content`         LONGTEXT     NOT NULL                COMMENT '资源正文或结构化内容',
    `version`         INT          NOT NULL DEFAULT 1      COMMENT '内容版本',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active/archived/rejected',
    `review_result`   JSON         DEFAULT NULL            COMMENT 'Supervisor审核结果',
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常，1=已删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_resource_id_version` (`resource_id`, `version`),
    KEY `idx_resource_id_status` (`resource_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习资源内容表';
