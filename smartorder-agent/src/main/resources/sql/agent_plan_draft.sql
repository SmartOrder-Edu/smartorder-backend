-- 智能体规划草稿表
-- planType: EXECUTION_PLAN（Agent 执行步骤列表）| LEARNING_PLAN（学习计划草稿）
-- execution_plan_json: 当 planType=EXECUTION_PLAN 时存储 ExecutionPlan JSON
-- plan_json: 当 planType=LEARNING_PLAN 时存储学习计划 JSON
CREATE TABLE IF NOT EXISTS `agent_plan_draft` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `draft_id`             VARCHAR(36)   NOT NULL                 COMMENT '规划草稿ID',
    `conversation_id`      VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `trace_id`             VARCHAR(36)   NOT NULL                 COMMENT '追踪ID',
    `user_id`              BIGINT        NOT NULL                 COMMENT '用户ID',
    `plan_json`            JSON          DEFAULT NULL             COMMENT '学习计划草稿JSON（planType=LEARNING_PLAN 时有值）',
    `execution_plan_json`  TEXT          DEFAULT NULL             COMMENT 'ExecutionPlan JSON（planType=EXECUTION_PLAN 时有值）',
    `plan_type`            VARCHAR(20)   DEFAULT 'LEARNING_PLAN'  COMMENT 'EXECUTION_PLAN（Agent执行规划）| LEARNING_PLAN（学习计划草稿）',
    `resume_step_index`    INT           DEFAULT 0                COMMENT '断点恢复：当前执行到的步骤索引',
    `status`               VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待确认、CONFIRMED已确认、CANCELLED已取消、EXPIRED已过期',
    `expire_time`          DATETIME      DEFAULT NULL             COMMENT '过期时间',
    `create_time`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft` (`draft_id`),
    INDEX `idx_conv` (`conversation_id`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体规划草稿表';
