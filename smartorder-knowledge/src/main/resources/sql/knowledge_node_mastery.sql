-- 知识节点掌握度表
CREATE TABLE IF NOT EXISTS `knowledge_node_mastery` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '掌握记录ID',
    `user_id`            BIGINT        NOT NULL                 COMMENT '用户ID',
    `node_id`            BIGINT        NOT NULL                 COMMENT '知识节点ID',
    `mastery_level`      DECIMAL(5,4)  NOT NULL DEFAULT 0.0000  COMMENT '掌握程度 0.0000-1.0000',
    `confidence`         DECIMAL(5,4)  NOT NULL DEFAULT 0.0000  COMMENT '置信度 0.0000-1.0000',
    `last_assessed_at`   DATETIME      DEFAULT NULL             COMMENT '最近一次评估时间',
    `assessment_count`   INT           NOT NULL DEFAULT 0       COMMENT '评估次数',
    `evidence_sources`   JSON          DEFAULT NULL             COMMENT '评估证据来源',
    `is_deleted`         TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_node` (`user_id`, `node_id`, `is_deleted`),
    KEY `idx_node_id` (`node_id`),
    KEY `idx_user_mastery` (`user_id`, `mastery_level`),
    KEY `idx_user_last_assessed` (`user_id`, `last_assessed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识节点掌握度表';
