-- 学习路径节点表
CREATE TABLE IF NOT EXISTS `learning_path_node` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '节点ID',
    `space_id`                   BIGINT       NOT NULL                 COMMENT '所属空间ID',
    `title`                      VARCHAR(200) NOT NULL                 COMMENT '节点标题',
    `description`                VARCHAR(2000) DEFAULT NULL            COMMENT '节点描述/学习目标详情',
    `node_type`                  VARCHAR(20)  NOT NULL DEFAULT 'objective' COMMENT '节点类型：module/stage/objective/knowledge_point',
    `estimated_duration_minutes` INT          DEFAULT NULL             COMMENT '预估学习时长（分钟）',
    `difficulty_level`           TINYINT      DEFAULT 1                COMMENT '难度等级：1=入门，5=专家',
    `sort_order`                 INT          NOT NULL DEFAULT 0       COMMENT '建议排序',
    `is_milestone`               TINYINT      NOT NULL DEFAULT 0       COMMENT '是否为里程碑节点：0=否，1=是',
    `icon_url`                   VARCHAR(500) DEFAULT NULL             COMMENT '节点图标',
    `metadata`                   JSON         DEFAULT NULL             COMMENT '扩展元数据',
    `is_deleted`                 TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_space_id` (`space_id`),
    KEY `idx_space_sort` (`space_id`, `sort_order`),
    KEY `idx_space_type` (`space_id`, `node_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习路径节点表';

-- ============================================================
-- 迭代演进 ALTER（已合并到 doc/db_migration_iter1.sql 由 DBA 手动执行）
-- ============================================================
-- 迭代一（草案完整物化）：加 parent_node_id / kp_uid / 三类时长字段
-- ALTER TABLE `learning_path_node`
--     ADD COLUMN `parent_node_id`          BIGINT       DEFAULT NULL COMMENT '父节点ID（stage节点为 NULL；knowledge_point 节点为所属 stage 的 ID）' AFTER `space_id`,
--     ADD COLUMN `kp_uid`                  VARCHAR(36)  DEFAULT NULL COMMENT '知识点全局UID（与 knowledge_graph_node.kp_uid 共享，仅 knowledge_point 节点必填）' AFTER `parent_node_id`,
--     ADD COLUMN `planned_duration_days`   INT          DEFAULT NULL COMMENT '计划时长（天） — 草案落库时记录，永久不变' AFTER `estimated_duration_minutes`,
--     ADD COLUMN `estimated_duration_days` INT          DEFAULT NULL COMMENT '预计时长（天） — 动态调整' AFTER `planned_duration_days`,
--     ADD COLUMN `actual_duration_days`    INT          DEFAULT NULL COMMENT '实际时长（天） — 节点完成后回填' AFTER `estimated_duration_days`,
--     ADD INDEX `idx_parent_node_id` (`parent_node_id`),
--     ADD INDEX `idx_space_kp_uid`   (`space_id`, `kp_uid`);
