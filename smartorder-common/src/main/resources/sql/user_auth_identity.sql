-- 用户第三方登录身份表（逻辑外键，无物理外键约束）
CREATE TABLE `user_auth_identity`
(
    `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`           bigint unsigned NOT NULL COMMENT '关联本地 user.id（逻辑外键）',
    `provider_code`     varchar(50) COLLATE utf8mb4_unicode_ci  NOT NULL COMMENT '第三方平台标识：github/google/gitee/baidu',
    `provider_subject`  varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '第三方平台稳定用户ID',
    `provider_username` varchar(100) COLLATE utf8mb4_unicode_ci          DEFAULT NULL COMMENT '第三方平台用户名',
    `provider_email`    varchar(100) COLLATE utf8mb4_unicode_ci          DEFAULT NULL COMMENT '第三方平台返回邮箱',
    `email_verified`    tinyint                                 NOT NULL DEFAULT '0' COMMENT '第三方邮箱是否已验证：0=未验证，1=已验证',
    `access_token`      text COLLATE utf8mb4_unicode_ci                  DEFAULT NULL COMMENT '第三方访问令牌，加密保存；不需要调用厂商API则不要存储',
    `refresh_token`     text COLLATE utf8mb4_unicode_ci                  DEFAULT NULL COMMENT '第三方刷新令牌，加密保存',
    `token_expire_time` datetime                                         DEFAULT NULL COMMENT '访问令牌过期时间',
    `scope`             varchar(500) COLLATE utf8mb4_unicode_ci          DEFAULT NULL COMMENT '第三方授权范围',
    `status`            tinyint                                 NOT NULL DEFAULT '1' COMMENT '身份状态：0=禁用，1=正常',
    `first_login_time`  datetime                                         DEFAULT NULL COMMENT '首次使用该身份登录时间',
    `last_login_time`   datetime                                         DEFAULT NULL COMMENT '最近使用该身份登录时间',
    `create_time`       datetime                                NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       datetime                                NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_provider_subject` (`provider_code`, `provider_subject`),
    UNIQUE KEY `uk_user_provider` (`user_id`, `provider_code`),
    KEY                 `idx_user_id` (`user_id`)
) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci
COMMENT='用户第三方登录身份表';