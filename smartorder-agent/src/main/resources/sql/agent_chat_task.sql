-- ============================================================
-- 对话任务表：一次用户发送对应一个任务，一条 assistant 消息对应一个任务（1:1）
-- ============================================================
-- 说明：
--   0. 如果库里已有旧版 agent_chat_task（带 request_id / stream_version 等列），
--      它是已废弃的旧 /agent/tasks 实现，字段与本表不兼容；
--      直接执行本文件不会覆盖旧表（CREATE TABLE IF NOT EXISTS），会继续报
--      “Unknown column 'assistant_message_id'”。
--      请改执行：docs/后端docs/sql/db_migration_agent_chat_task_repair.sql
--      （旧表非空时自动改名为 agent_chat_task_legacy_<时间戳> 留档，再重建本表）。
--   1. task_id 使用 UUID；同一个任务的 user / assistant 两条 conversation_message
--      都写入 conversation_message.task_id = agent_chat_task.task_id。
--   2. Redis 只保存任务生成过程中的事件片段（Stream），任务状态与归属以本表为准。
--   3. (user_id, task_request_id) 是发送幂等的最终防线；task_request_id 可为空，
--      MySQL 唯一索引允许多个 NULL，因此不传幂等键的请求不会互相阻塞。
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent_chat_task` (
    `task_id`               VARCHAR(36)   NOT NULL                 COMMENT '对话任务ID（UUID）',
    `conversation_id`       VARCHAR(36)   DEFAULT NULL             COMMENT '会话 conversation_id；openReply 前允许为空',
    `draft_id`              VARCHAR(36)   DEFAULT NULL             COMMENT '客户端草稿ID；同一 requestId 只允许绑定同一个 draftId',
    `user_id`               BIGINT        NOT NULL                 COMMENT '任务归属用户',
    `user_message_id`       BIGINT        DEFAULT NULL             COMMENT '本轮用户消息行ID',
    `assistant_message_id`  BIGINT        DEFAULT NULL             COMMENT '本轮 assistant 占位行ID',
    `assistant_sequence_no` INT           DEFAULT NULL             COMMENT 'assistant 占位行序号',
    `task_request_id`       VARCHAR(100)  DEFAULT NULL             COMMENT '任务请求幂等键（前端 taskRequestId）',
    `trace_id`              VARCHAR(36)   DEFAULT NULL             COMMENT '本轮追踪ID',
    `status`                VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED',
    `last_seq`              BIGINT        NOT NULL DEFAULT 0       COMMENT '终态回写的事件最大 seq',
    `title`                 VARCHAR(500)  DEFAULT NULL             COMMENT '会话兜底标题',
    `error_message`         VARCHAR(500)  DEFAULT NULL             COMMENT '失败/中断原因',
    `replayable`            TINYINT(1)    NOT NULL DEFAULT 1       COMMENT '事件是否完整可回放：1是，0否',
    `created_at`            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `finished_at`           DATETIME(3)   DEFAULT NULL             COMMENT '终态时间',
    PRIMARY KEY (`task_id`),
    UNIQUE KEY `uk_agent_task_request` (`user_id`, `task_request_id`),
    KEY `idx_agent_task_conv` (`conversation_id`, `created_at`),
    KEY `idx_agent_task_draft` (`user_id`, `draft_id`),
    KEY `idx_agent_task_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可恢复的 Agent 对话任务';
