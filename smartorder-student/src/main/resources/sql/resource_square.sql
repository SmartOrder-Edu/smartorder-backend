-- 资源广场发布表
CREATE TABLE `resource_square`
(
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '广场资源ID',
    `title`          VARCHAR(200)  NOT NULL COMMENT '资源标题',
    `description`    VARCHAR(1000) DEFAULT NULL COMMENT '资源描述',
    `tags`           VARCHAR(500)  DEFAULT NULL COMMENT '标签，JSON数组格式',
    `category`       VARCHAR(30)   NOT NULL DEFAULT 'doc' COMMENT '分类：doc/mindmap/video/quiz/case',
    `user_id`        BIGINT        NOT NULL COMMENT '发布者用户ID',
    `file_id`        BIGINT        NOT NULL COMMENT '关联的原始文件ID（file_resource.id）',
    `cover_url`      VARCHAR(500)  DEFAULT NULL COMMENT '封面图URL',
    `download_count` INT           NOT NULL DEFAULT 0 COMMENT '下载/转存次数',
    `view_count`     INT           NOT NULL DEFAULT 0 COMMENT '浏览次数',
    `rating_avg`     DECIMAL(2,1)  NOT NULL DEFAULT 0.0 COMMENT '平均评分（1.0-5.0）',
    `rating_count`   INT           NOT NULL DEFAULT 0 COMMENT '评分人数',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0-已下架，1-已发布',
    `is_deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_category` (`category`),
    KEY `idx_status` (`status`),
    KEY `idx_download_count` (`download_count`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_file_id` (`file_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='资源广场发布表';

-- 资源广场评分表
CREATE TABLE `resource_square_rating`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '评分ID',
    `square_id`   BIGINT   NOT NULL COMMENT '资源广场ID',
    `user_id`     BIGINT   NOT NULL COMMENT '评分用户ID',
    `rating`      TINYINT  NOT NULL COMMENT '评分：1-5',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评分时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_square_user` (`square_id`, `user_id`),
    KEY `idx_square_id` (`square_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='资源广场评分表';

-- 资源广场转存记录表
CREATE TABLE `resource_square_download`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '下载记录ID',
    `square_id`   BIGINT   NOT NULL COMMENT '资源广场ID',
    `user_id`     BIGINT   NOT NULL COMMENT '下载用户ID',
    `new_file_id` BIGINT DEFAULT NULL COMMENT '转存后生成的新file_resource.id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下载时间',
    PRIMARY KEY (`id`),
    KEY `idx_square_id` (`square_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='资源广场下载记录表';
