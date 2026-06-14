-- 知识图谱节点表
CREATE TABLE IF NOT EXISTS `knowledge_graph_node` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '知识节点ID',
    `space_id`    BIGINT        NOT NULL                 COMMENT '所属空间ID',
    `name`        VARCHAR(200)  NOT NULL                 COMMENT '知识点名称',
    `description` VARCHAR(1000) DEFAULT NULL             COMMENT '知识点描述',
    `category`    VARCHAR(50)   DEFAULT NULL             COMMENT '分类标签：concept/skill/topic/method/fact',
    `importance`  TINYINT       NOT NULL DEFAULT 3       COMMENT '重要程度：1=了解，5=核心掌握',
    `metadata`    JSON          DEFAULT NULL             COMMENT '扩展元数据',
    `is_deleted`  TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_space_id` (`space_id`),
    KEY `idx_space_category` (`space_id`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识图谱节点表';

-- ============================================================
-- 迭代演进 ALTER（已合并到 doc/db_migration_iter1.sql 由 DBA 手动执行）
-- ============================================================
-- 迭代一（草案完整物化）：加 kp_uid，与 learning_path_node.kp_uid 共享
-- ALTER TABLE `knowledge_graph_node`
--     ADD COLUMN `kp_uid` VARCHAR(36) DEFAULT NULL COMMENT '知识点全局UID（与 learning_path_node.kp_uid 一致）' AFTER `name`,
--     ADD INDEX `idx_space_kp_uid` (`space_id`, `kp_uid`);
