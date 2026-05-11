-- 评估配置表（测验/考试）
CREATE TABLE IF NOT EXISTS `assessment` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '评估ID',
    `space_id`                 BIGINT       NOT NULL                 COMMENT '所属空间ID',
    `path_node_id`             BIGINT       DEFAULT NULL             COMMENT '关联的路径节点ID',
    `title`                    VARCHAR(200) NOT NULL                 COMMENT '评估标题',
    `description`              VARCHAR(1000) DEFAULT NULL            COMMENT '评估描述/说明',
    `assessment_type`          VARCHAR(20)  NOT NULL DEFAULT 'quiz'  COMMENT '评估类型：quiz/exam/diagnostic',
    `time_limit_minutes`       INT          DEFAULT NULL             COMMENT '时间限制（分钟），null=不限时',
    `total_score`              DECIMAL(6,1) NOT NULL DEFAULT 100.0   COMMENT '总分',
    `pass_score`               DECIMAL(6,1) DEFAULT NULL             COMMENT '及格分数线',
    `shuffle_questions`        TINYINT      NOT NULL DEFAULT 0       COMMENT '是否打乱题目：0=否，1=是',
    `show_result_immediately`  TINYINT      NOT NULL DEFAULT 1       COMMENT '是否立即显示结果：0=否，1=是',
    `attempt_limit`            INT          DEFAULT NULL             COMMENT '允许尝试次数，null=无限',
    `status`                   VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT '状态：draft/published/archived',
    `is_deleted`               TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_space_id` (`space_id`),
    KEY `idx_path_node_id` (`path_node_id`),
    KEY `idx_space_status` (`space_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估表';
