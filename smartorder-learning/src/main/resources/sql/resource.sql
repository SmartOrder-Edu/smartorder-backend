-- 学习资源表
CREATE TABLE IF NOT EXISTS `resource` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '资源ID',
    `space_id`          BIGINT        NOT NULL                 COMMENT '所属空间ID',
    `path_node_id`      BIGINT        DEFAULT NULL             COMMENT '关联的路径节点ID',
    `task_id`           BIGINT        DEFAULT NULL             COMMENT '关联的任务ID',
    `title`             VARCHAR(200)  NOT NULL                 COMMENT '资源标题',
    `description`       VARCHAR(1000) DEFAULT NULL             COMMENT '资源描述',
    `resource_type`     VARCHAR(30)   NOT NULL                 COMMENT '资源类型：ppt/doc/video/mind_map/quiz/case_study/flashcard_set/image/audio/link',
    `url`               VARCHAR(1000) NOT NULL                 COMMENT '资源URL',
    `file_format`       VARCHAR(20)   DEFAULT NULL             COMMENT '文件格式：pdf/mp4/pptx/docx/png/mp3/md',
    `file_size_bytes`   BIGINT        DEFAULT NULL             COMMENT '文件大小（字节）',
    `duration_seconds`  INT           DEFAULT NULL             COMMENT '时长（秒）',
    `cover_url`         VARCHAR(500)  DEFAULT NULL             COMMENT '封面图URL',
    `is_ai_generated`   TINYINT       NOT NULL DEFAULT 0       COMMENT '是否AI生成：0=否，1=是',
    `source`            VARCHAR(20)   NOT NULL DEFAULT 'user'  COMMENT '来源：ai/user/curated',
    `status`            VARCHAR(20)   NOT NULL DEFAULT 'generated' COMMENT '资源状态：planned/generating/generated/failed',
    `metadata`          JSON          DEFAULT NULL             COMMENT '扩展元数据',
    `is_deleted`        TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_space_id_type` (`space_id`, `resource_type`),
    KEY `idx_space_status` (`space_id`, `status`),
    KEY `idx_path_node_id` (`path_node_id`),
    KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习资源表';

-- ============================================================
-- 迭代演进 ALTER（已合并到 doc/db_migration_iter1.sql 由 DBA 手动执行）
-- ============================================================
-- 迭代一（草案完整物化）：加 status（资源生命周期）
-- 默认 'generated' 是为了不破坏现有数据语义；新草案落库的 phase.resources 写为 'planned'
-- ALTER TABLE `resource`
--     ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'generated' COMMENT '资源状态：planned/generating/generated/failed' AFTER `source`,
--     ADD INDEX `idx_space_status` (`space_id`, `status`);
