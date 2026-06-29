-- 教学动作表
CREATE TABLE IF NOT EXISTS `study_teaching_action` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `action_id`   VARCHAR(80)  NOT NULL                COMMENT '教学动作业务ID',
    `event_id`    VARCHAR(80)  DEFAULT NULL            COMMENT '来源事件业务ID',
    `session_id`  VARCHAR(80)  NOT NULL                COMMENT '会话业务ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `action_type` VARCHAR(60)  NOT NULL                COMMENT '动作类型',
    `scene_type`  VARCHAR(60)  DEFAULT NULL            COMMENT '作用场景类型',
    `reason`      VARCHAR(500) DEFAULT NULL            COMMENT '触发原因',
    `payload`     JSON         DEFAULT NULL            COMMENT '动作载荷',
    `status`      VARCHAR(30)  NOT NULL DEFAULT 'pending' COMMENT '动作状态：pending/applied/failed',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_action_id` (`action_id`),
    KEY `idx_event_id` (`event_id`),
    KEY `idx_session_id_status` (`session_id`, `status`),
    KEY `idx_user_id_action_type` (`user_id`, `action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教学动作表';
