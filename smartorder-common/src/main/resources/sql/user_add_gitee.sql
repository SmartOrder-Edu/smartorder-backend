-- =========================================================================
-- user 表添加 Gitee OAuth 登录相关字段
-- =========================================================================
ALTER TABLE `user`
    ADD COLUMN `gitee_id` VARCHAR(50) DEFAULT NULL COMMENT 'Gitee用户唯一标识' AFTER `github_username`,
    ADD COLUMN `gitee_username` VARCHAR(100) DEFAULT NULL COMMENT 'Gitee用户名' AFTER `gitee_id`,
    ADD UNIQUE KEY `uk_gitee_id` (`gitee_id`);
