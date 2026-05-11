-- 知识图谱边表（关系）
CREATE TABLE IF NOT EXISTS `knowledge_graph_edge` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '边ID',
    `space_id`        BIGINT        NOT NULL                 COMMENT '所属空间ID（冗余）',
    `from_node_id`    BIGINT        NOT NULL                 COMMENT '源节点ID',
    `to_node_id`      BIGINT        NOT NULL                 COMMENT '目标节点ID',
    `relation_type`   VARCHAR(30)   NOT NULL DEFAULT 'related_to' COMMENT '关系类型：prerequisite_of/related_to/part_of/extends/contradicts/applies_to',
    `weight`          DECIMAL(4,3)  NOT NULL DEFAULT 1.000   COMMENT '关系权重 0.000-1.000',
    `is_deleted`      TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge` (`from_node_id`, `to_node_id`, `relation_type`, `is_deleted`),
    KEY `idx_space_id` (`space_id`),
    KEY `idx_to_node_id` (`to_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识图谱边表';
