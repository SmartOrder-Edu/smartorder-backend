-- 学习路径边表（DAG依赖关系）
CREATE TABLE IF NOT EXISTS `learning_path_edge` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT  COMMENT '边ID',
    `space_id`       BIGINT      NOT NULL                 COMMENT '所属空间ID（冗余，加速空间级查询）',
    `from_node_id`   BIGINT      NOT NULL                 COMMENT '前置节点ID',
    `to_node_id`     BIGINT      NOT NULL                 COMMENT '后置节点ID',
    `edge_type`      VARCHAR(30) NOT NULL DEFAULT 'prerequisite_of' COMMENT '边类型：prerequisite_of/recommended_before',
    `is_deleted`     TINYINT     NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge` (`from_node_id`, `to_node_id`, `edge_type`, `is_deleted`),
    KEY `idx_space_id` (`space_id`),
    KEY `idx_to_node_id` (`to_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习路径边表';
