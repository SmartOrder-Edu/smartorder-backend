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

-- ============================================================
-- 迭代演进 ALTER（已合并到 doc/db_migration_iter1.sql 由 DBA 手动执行）
-- ============================================================
-- 迭代一（草案完整物化）：edge_type 注释扩展（contains 新枚举值，无字段类型变化）
-- 用于 stage 节点 contains 子 knowledge_point 节点
-- ALTER TABLE `learning_path_edge`
--     MODIFY COLUMN `edge_type` VARCHAR(30) NOT NULL DEFAULT 'prerequisite_of' COMMENT '边类型：prerequisite_of/recommended_before/contains';
