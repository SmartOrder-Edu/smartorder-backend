-- 学习路径节点表
CREATE TABLE IF NOT EXISTS `learning_path_node` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '节点ID',
    `space_id`                   BIGINT       NOT NULL                 COMMENT '所属空间ID',
    `title`                      VARCHAR(200) NOT NULL                 COMMENT '节点标题',
    `description`                VARCHAR(2000) DEFAULT NULL            COMMENT '节点描述/学习目标详情',
    `node_type`                  VARCHAR(20)  NOT NULL DEFAULT 'objective' COMMENT '节点类型：module/stage/objective/knowledge_point',
    `estimated_duration_minutes` INT          DEFAULT NULL             COMMENT '预估学习时长（分钟）',
    `difficulty_level`           TINYINT      DEFAULT 1                COMMENT '难度等级：1=入门，5=专家',
    `sort_order`                 INT          NOT NULL DEFAULT 0       COMMENT '建议排序',
    `is_milestone`               TINYINT      NOT NULL DEFAULT 0       COMMENT '是否为里程碑节点：0=否，1=是',
    `icon_url`                   VARCHAR(500) DEFAULT NULL             COMMENT '节点图标',
    `metadata`                   JSON         DEFAULT NULL             COMMENT '扩展元数据',
    `is_deleted`                 TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_space_id` (`space_id`),
    KEY `idx_space_sort` (`space_id`, `sort_order`),
    KEY `idx_space_type` (`space_id`, `node_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习路径节点表';
