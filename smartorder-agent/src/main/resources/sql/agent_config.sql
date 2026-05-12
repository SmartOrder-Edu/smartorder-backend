-- Agent配置表（每个学习空间可自定义Agent）
CREATE TABLE IF NOT EXISTS `agent_config` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '配置ID',
    `space_id`                 BIGINT        NOT NULL                 COMMENT '所属空间ID（一对一）',
    `agent_name`               VARCHAR(100)  DEFAULT NULL             COMMENT 'Agent自定义名称',
    `agent_avatar_url`         VARCHAR(500)  DEFAULT NULL             COMMENT 'Agent头像URL',
    `model`                    VARCHAR(50)   DEFAULT NULL             COMMENT 'LLM模型，null=使用系统默认',
    `temperature`              DECIMAL(3,2)  DEFAULT NULL             COMMENT '温度参数 0.00-2.00',
    `max_tokens`               INT           DEFAULT NULL             COMMENT '最大输出token数',
    `system_prompt_addition`   TEXT          DEFAULT NULL             COMMENT '系统提示词追加内容',
    `enabled_tools`            JSON          DEFAULT NULL             COMMENT '启用的工具列表',
    `persona`                  VARCHAR(50)   DEFAULT NULL             COMMENT 'Agent人设：tutor/coach/mentor/companion/strict_teacher',
    `custom_instructions`      TEXT          DEFAULT NULL             COMMENT '自定义行为指令',
    `is_enabled`               TINYINT       NOT NULL DEFAULT 1       COMMENT '是否启用：0=禁用，1=启用',
    `is_deleted`               TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_space_id` (`space_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习空间Agent配置表';
