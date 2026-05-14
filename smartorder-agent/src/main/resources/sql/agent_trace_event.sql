-- 智能体追踪事件日志表
CREATE TABLE IF NOT EXISTS `agent_trace_event` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `conversation_id`  VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `trace_id`         VARCHAR(36)   NOT NULL                 COMMENT '追踪ID',
    `user_id`          BIGINT        NOT NULL                 COMMENT '用户ID',
    `agent_mode`       VARCHAR(30)   NOT NULL                 COMMENT '智能体模式：REACT、PLAN_EXECUTE、MULTI_AGENT',
    `event_type`       VARCHAR(80)   NOT NULL                 COMMENT '事件类型',
    `sequence_num`     INT           NOT NULL                 COMMENT '事件序号',
    `payload`          JSON          NOT NULL                 COMMENT '事件载荷JSON',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_conv` (`conversation_id`),
    INDEX `idx_trace` (`trace_id`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_trace_seq` (`trace_id`, `sequence_num`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体追踪事件日志表';
