-- 智能体工具执行审计日志表
CREATE TABLE IF NOT EXISTS `agent_tool_execution` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    `trace_id`         VARCHAR(36)   NOT NULL                 COMMENT '追踪ID',
    `conversation_id`  VARCHAR(36)   NOT NULL                 COMMENT '会话ID',
    `user_id`          BIGINT        NOT NULL                 COMMENT '用户ID',
    `tool_name`        VARCHAR(100)  NOT NULL                 COMMENT '工具名称',
    `arguments`        JSON          DEFAULT NULL             COMMENT '工具调用参数',
    `result`           JSON          DEFAULT NULL             COMMENT '工具返回结果，可能为截断结果',
    `status`           VARCHAR(20)   NOT NULL                 COMMENT '状态：SUCCESS、FAILED、TIMEOUT、REJECTED',
    `error_message`    TEXT          DEFAULT NULL             COMMENT '错误信息',
    `duration_ms`      INT           DEFAULT NULL             COMMENT '执行耗时毫秒',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_trace` (`trace_id`),
    INDEX `idx_conv` (`conversation_id`),
    INDEX `idx_tool` (`tool_name`),
    INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体工具执行审计日志表';
