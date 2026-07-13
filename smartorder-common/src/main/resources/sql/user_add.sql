-- =========================================================================
-- 1. 本地业务账号表 user 的调整
-- =========================================================================

-- 【修改语句】针对现有数据库的表结构升级
ALTER TABLE `user`
    ADD COLUMN `email_verified` TINYINT NOT NULL DEFAULT 0 COMMENT '邮箱是否已验证：0=未验证，1=已验证' AFTER `email`,
    MODIFY COLUMN `password` VARCHAR(255) DEFAULT NULL COMMENT '密码（BCrypt加密，纯第三方账号可为空）';

-- 【完整建表语句】修改调整后的完整 user 表
CREATE TABLE IF NOT EXISTS `user` (
                                      `id`              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '用户ID',
                                      `username`        VARCHAR(50)  NOT NULL                 COMMENT '用户名/昵称',
    `email`           VARCHAR(100) DEFAULT NULL             COMMENT '邮箱',
    `email_verified`  TINYINT      NOT NULL DEFAULT 0       COMMENT '邮箱是否已验证：0=未验证，1=已验证',
    `phone`           VARCHAR(20)  DEFAULT NULL             COMMENT '手机号',
    `password`        VARCHAR(255) DEFAULT NULL             COMMENT '密码（BCrypt加密，纯第三方账号可为空）',
    `avatar_url`      VARCHAR(500) DEFAULT NULL             COMMENT '头像URL',
    `gender`          TINYINT      DEFAULT 0                COMMENT '性别：0=未设置，1=男，2=女',
    `birthday`        DATE         DEFAULT NULL             COMMENT '生日',
    `bio`             VARCHAR(500) DEFAULT NULL             COMMENT '个人简介',
    `role`            TINYINT      NOT NULL DEFAULT 0       COMMENT '角色：0=学生，1=管理员',
    `vip_level`       TINYINT      NOT NULL DEFAULT 0       COMMENT 'VIP等级：0=普通用户',
    `vip_start_time`  DATETIME     DEFAULT NULL             COMMENT 'VIP开始时间',
    `vip_expire_time` DATETIME     DEFAULT NULL             COMMENT 'VIP到期时间',
    `status`          TINYINT      NOT NULL DEFAULT 1       COMMENT '账号状态：0=禁用，1=正常',
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除：0=正常，1=已删除',
    `github_id`       VARCHAR(50)  DEFAULT NULL             COMMENT 'GitHub用户唯一标识（第一阶段保留）',
    `github_username` VARCHAR(100) DEFAULT NULL             COMMENT 'GitHub用户名（第一阶段保留）',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_email` (`email`),
    UNIQUE KEY `uk_phone` (`phone`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_github_id` (`github_id`),
    KEY `idx_role` (`role`),
    KEY `idx_vip_expire_time` (`vip_expire_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户业务账号表';


-- =========================================================================
-- 2. Auth0 用户账号表 user_auth_account (一行代表一个 Auth0 sub)
-- =========================================================================
CREATE TABLE IF NOT EXISTS `user_auth_account` (
                                                   `id`               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
                                                   `user_id`          BIGINT       NOT NULL                 COMMENT '关联本地 user.id',
                                                   `issuer`           VARCHAR(255) NOT NULL                 COMMENT 'Auth0 OIDC Issuer',
    `auth0_subject`    VARCHAR(255) NOT NULL                 COMMENT 'ID Token 的 sub',
    `current_email`    VARCHAR(100) DEFAULT NULL             COMMENT 'Auth0 当前返回邮箱',
    `email_verified`   TINYINT      NOT NULL DEFAULT 0       COMMENT 'Auth0 邮箱是否可信/已验证：0=未验证，1=已验证',
    `display_name`     VARCHAR(100) DEFAULT NULL             COMMENT '第三方展示名称',
    `picture_url`      VARCHAR(500) DEFAULT NULL             COMMENT '第三方头像',
    `status`           TINYINT      NOT NULL DEFAULT 1       COMMENT '账号状态：0=禁用，1=正常',
    `first_login_time` DATETIME     DEFAULT NULL             COMMENT '首次登录时间',
    `last_login_time`  DATETIME     DEFAULT NULL             COMMENT '最近登录时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_issuer_subject` (`issuer`, `auth0_subject`),
    KEY `idx_user_id` (`user_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Auth0 用户账号表';


-- =========================================================================
-- 3. 具体平台第三方身份表 user_auth_identity (一行代表一个三方平台具体身份)
-- =========================================================================
CREATE TABLE IF NOT EXISTS `user_auth_identity` (
                                                    `id`               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
                                                    `auth_account_id`  BIGINT       NOT NULL                 COMMENT '关联 user_auth_account.id',
                                                    `provider_code`    VARCHAR(50)  NOT NULL                 COMMENT '第三方平台标识：github/google/gitee/baidu',
    `connection_name`  VARCHAR(100) NOT NULL                 COMMENT 'Auth0 Connection 名称',
    `strategy`         VARCHAR(100) NOT NULL                 COMMENT 'Auth0 策略，如 github、google-oauth2、oauth2',
    `provider_subject` VARCHAR(255) NOT NULL                 COMMENT '平台自身稳定用户 ID',
    `provider_username` VARCHAR(100) DEFAULT NULL             COMMENT '平台用户名',
    `provider_email`   VARCHAR(100) DEFAULT NULL             COMMENT '平台返回邮箱',
    `email_verified`   TINYINT      NOT NULL DEFAULT 0       COMMENT '平台邮箱是否可信/已验证：0=未验证，1=已验证',
    `last_login_time`  DATETIME     DEFAULT NULL             COMMENT '最近使用此身份登录的时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_auth_account_id` (`auth_account_id`),
    UNIQUE KEY `uk_provider_identity` (`provider_code`, `provider_subject`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='具体平台第三方身份表';