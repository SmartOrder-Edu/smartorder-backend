-- ============================================================
-- 迭代二：学习任务可执行入口字段 — 数据库迁移脚本（幂等版）
-- ============================================================
-- 执行环境：smart_order_db
-- 执行方式：手动 mysql 客户端导入（可重复执行不报错）
--
-- 设计说明：
--   task_type 保留为计划类型：daily/milestone/exam/review/remedial/project
--   action_type 表示执行动作：learn_script/read_resource/watch_video/practice_set/assessment/...
--   target_type + target_id 指向真实可执行对象，例如 path_node/resource/practice_session
-- ============================================================

USE `smart_order_db`;

DROP PROCEDURE IF EXISTS `pr_iter2_add_column`;
DROP PROCEDURE IF EXISTS `pr_iter2_add_index`;
DROP PROCEDURE IF EXISTS `pr_iter2_modify_column`;

DELIMITER $$

CREATE PROCEDURE `pr_iter2_add_column`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

CREATE PROCEDURE `pr_iter2_add_index`(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

CREATE PROCEDURE `pr_iter2_modify_column`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

CALL pr_iter2_modify_column('task', 'task_type',
    'VARCHAR(20) NOT NULL DEFAULT ''daily'' COMMENT ''计划类型：daily/milestone/exam/review/remedial/project''');

CALL pr_iter2_add_column('task', 'action_type',
    'VARCHAR(40) NOT NULL DEFAULT ''learn_script'' COMMENT ''执行动作类型：learn_script/read_resource/watch_video/practice_set/assessment/mistake_review/project_practice/summary_reflection'' AFTER `task_type`');
CALL pr_iter2_add_column('task', 'target_type',
    'VARCHAR(40) DEFAULT NULL COMMENT ''执行目标类型：lesson_bundle/resource/practice_session/assessment_session/knowledge_node/path_node等'' AFTER `action_type`');
CALL pr_iter2_add_column('task', 'target_id',
    'BIGINT DEFAULT NULL COMMENT ''执行目标ID'' AFTER `target_type`');
CALL pr_iter2_add_column('task', 'agent_reason',
    'VARCHAR(1000) DEFAULT NULL COMMENT ''Agent安排该任务的原因'' AFTER `ai_suggestion`');

CALL pr_iter2_add_index('task', 'idx_space_action_status', '`space_id`, `action_type`, `status`');
CALL pr_iter2_add_index('task', 'idx_target', '`target_type`, `target_id`');

DROP PROCEDURE IF EXISTS `pr_iter2_add_column`;
DROP PROCEDURE IF EXISTS `pr_iter2_add_index`;
DROP PROCEDURE IF EXISTS `pr_iter2_modify_column`;

-- 验证：
-- SHOW COLUMNS FROM `task`;
