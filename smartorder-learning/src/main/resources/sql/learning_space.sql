-- 学习空间表
CREATE TABLE IF NOT EXISTS `learning_space` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '空间ID',
    `user_id`           BIGINT       NOT NULL                 COMMENT '所属用户ID',
    `name`              VARCHAR(100) NOT NULL                 COMMENT '空间名称',
    `type`              VARCHAR(20)  NOT NULL DEFAULT 'course' COMMENT '空间类型：course/exam/project/career/competition',
    `description`       VARCHAR(1000) DEFAULT NULL            COMMENT '空间描述',
    `icon_url`          VARCHAR(500)  DEFAULT NULL            COMMENT '图标URL',
    `cover_url`         VARCHAR(500)  DEFAULT NULL            COMMENT '封面图URL',
    `goals`             JSON          DEFAULT NULL            COMMENT '目标列表',
    `progress`          DECIMAL(5,2)  NOT NULL DEFAULT 0.00   COMMENT '整体进度百分比 0.00-100.00',
    `status`            VARCHAR(20)   NOT NULL DEFAULT 'active' COMMENT '状态：active/archived/completed',
    `start_date`        DATE          DEFAULT NULL            COMMENT '计划开始日期',
    `target_end_date`   DATE          DEFAULT NULL            COMMENT '目标完成日期',
    `actual_end_date`   DATE          DEFAULT NULL            COMMENT '实际完成日期',
    `sort_order`        INT           NOT NULL DEFAULT 0      COMMENT '用户自定义排序',
    `is_deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常，1=已删除',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id_status` (`user_id`, `status`),
    KEY `idx_user_id_sort` (`user_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习空间表';
