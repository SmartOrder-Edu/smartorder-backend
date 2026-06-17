-- ============================================================
-- Agent 运行状态表 (AgentRunState)
-- 一次 Agent Run 作为一等对象管理，记录 Plan-and-Execute 全生命周期
-- ============================================================
CREATE TABLE IF NOT EXISTS `agent_run_state` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `run_id`                VARCHAR(36)   NOT NULL                 COMMENT '运行唯一ID (UUID)',
    `conversation_id`       VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `draft_id`              VARCHAR(36)   NOT NULL                 COMMENT '关联的规划草稿ID',
    `trace_id`              VARCHAR(36)   DEFAULT NULL             COMMENT '当前追踪ID',
    `user_id`               BIGINT        NOT NULL                 COMMENT '用户ID',
    `status`                VARCHAR(30)   NOT NULL DEFAULT 'PLANNING'  COMMENT 'PLANNING | WAITING_CONFIRMATION | EXECUTING | PAUSED_CLARIFICATION | PAUSED_CONFIRMATION | COMPLETED | FAILED | CANCELLED',
    `current_step_index`    INT           DEFAULT 0                COMMENT '当前执行步骤索引',
    `paused_reason`         VARCHAR(30)   DEFAULT NULL             COMMENT '暂停原因: CLARIFICATION | CONFIRMATION',
    `paused_task_id`        VARCHAR(20)   DEFAULT NULL             COMMENT '导致暂停的任务ID',
    `paused_clarification_id` VARCHAR(36) DEFAULT NULL             COMMENT '当前活跃的澄清ID',
    `paused_question`       VARCHAR(500)  DEFAULT NULL             COMMENT '暂停时展示给用户的问题',
    `result_summary`        TEXT          DEFAULT NULL             COMMENT '完成/失败时的结果摘要',
    `create_time`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `completed_at`          DATETIME      DEFAULT NULL             COMMENT '完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run` (`run_id`),
    INDEX `idx_conv` (`conversation_id`),
    INDEX `idx_draft` (`draft_id`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 运行状态表——执行全生命周期的权威数据源';
