-- ============================================================
-- Agent 澄清记录表 (Clarification Gate)
-- 每个 slot-question 一行，强制"一次只问一个"原则
-- ============================================================
CREATE TABLE IF NOT EXISTS `agent_clarification` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `clarification_id`   VARCHAR(36)   NOT NULL                 COMMENT '澄清记录唯一ID (UUID)',
    `conversation_id`    VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `trace_id`           VARCHAR(36)   NOT NULL                 COMMENT '追踪ID',
    `user_id`            BIGINT        NOT NULL                 COMMENT '用户ID',
    `draft_id`           VARCHAR(36)   NOT NULL                 COMMENT '关联的规划草稿ID (agent_plan_draft.draft_id)',
    `task_id`            VARCHAR(20)   NOT NULL                 COMMENT '关联的步骤ID (如 step-1)',
    `slot_key`           VARCHAR(50)   NOT NULL                 COMMENT '槽位标识 (如 target_scope, current_level)',
    `question`           VARCHAR(500)  NOT NULL                 COMMENT '向用户提出的问题',
    `answer_type`        VARCHAR(20)   NOT NULL DEFAULT 'TEXT'  COMMENT '答案类型: TEXT | SINGLE_SELECT | MULTI_SELECT | NUMBER',
    `options_json`       JSON          DEFAULT NULL             COMMENT '选项列表 (answer_type 为 SELECT 时有值), 格式: [{label, value}]',
    `answer`             TEXT          DEFAULT NULL             COMMENT '用户的回答 (文本或选中值的JSON)',
    `status`             VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | ANSWERED | ACKNOWLEDGED | SKIPPED',
    `ack_message`        VARCHAR(500)  DEFAULT NULL             COMMENT 'Agent 对用户回答的确认文本',
    `slot_priority`      INT           DEFAULT 0                COMMENT '槽位优先级 (数字越小越先问)',
    `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `answer_time`        DATETIME      DEFAULT NULL             COMMENT '用户回答时间',
    `ack_time`           DATETIME      DEFAULT NULL             COMMENT 'Agent确认时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clarification` (`clarification_id`),
    INDEX `idx_conv` (`conversation_id`),
    INDEX `idx_draft` (`draft_id`),
    INDEX `idx_draft_task` (`draft_id`, `task_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 澄清记录表';
