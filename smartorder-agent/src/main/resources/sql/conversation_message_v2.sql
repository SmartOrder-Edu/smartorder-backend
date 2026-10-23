-- ============================================================
-- conversation_message 扩展（会话内序号 + 消息状态机 + 版本化重生成）
-- ============================================================
-- 适用场景：全新库（表里没有历史数据）直接执行；
--           已有数据请走 docs/后端docs/sql/db_migration_conversation.sql（含回填）。
-- ============================================================

ALTER TABLE `conversation_message`
  ADD COLUMN `public_id`         VARCHAR(36)  DEFAULT NULL COMMENT '对外消息ID（UUID），前端只认这个' AFTER `trace_id`,
  ADD COLUMN `sequence_no`       INT          NOT NULL DEFAULT 0 COMMENT '会话内序号，从1开始，由会话行锁分配' AFTER `public_id`,
  ADD COLUMN `status`            VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED' COMMENT 'RUNNING=生成中, COMPLETED=完成, FAILED=失败, CANCELLED=用户中止' AFTER `sequence_no`,
  ADD COLUMN `parent_message_id` BIGINT       DEFAULT NULL COMMENT '父消息：assistant 消息指向触发它的 user 消息' AFTER `status`,
  ADD COLUMN `attempt_no`        INT          NOT NULL DEFAULT 1 COMMENT '同一 parent 下的第几次生成（重新生成递增）' AFTER `parent_message_id`,
  ADD COLUMN `is_visible`        TINYINT      NOT NULL DEFAULT 1 COMMENT '版本切换：同一 parent 下仅一条为1' AFTER `attempt_no`,
  ADD COLUMN `model_name`        VARCHAR(64)  DEFAULT NULL COMMENT '生成该消息的模型' AFTER `is_visible`,
  ADD COLUMN `prompt_tokens`     INT          DEFAULT NULL,
  ADD COLUMN `output_tokens`     INT          DEFAULT NULL,
  ADD COLUMN `latency_ms`        INT          DEFAULT NULL COMMENT '占位行创建到收尾的耗时（毫秒）',
  ADD COLUMN `started_at`        DATETIME(3)  DEFAULT NULL COMMENT '占位行创建时间（RUNNING 起点）',
  ADD COLUMN `completed_at`      DATETIME(3)  DEFAULT NULL,
  ADD COLUMN `error_message`     VARCHAR(500) DEFAULT NULL;

-- 会话内序号唯一：保证 LIMIT/OFFSET 分页稳定，也是并发写入的最终防线
ALTER TABLE `conversation_message`
  ADD UNIQUE KEY `uk_conv_seq` (`conversation_id`, `sequence_no`),
  ADD KEY `idx_conv_status` (`conversation_id`, `status`, `is_visible`),
  ADD KEY `idx_parent` (`parent_message_id`),
  ADD KEY `idx_status_started` (`status`, `started_at`);
