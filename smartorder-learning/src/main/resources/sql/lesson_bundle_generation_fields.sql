-- 已有 lesson_bundle 表增量脚本：补充学习脚本生成触发、有效期和错误状态字段
ALTER TABLE `lesson_bundle`
    ADD COLUMN `generation_trigger` VARCHAR(40) DEFAULT NULL COMMENT '生成触发源：manual/after_complete/system' AFTER `generation_context`,
    ADD COLUMN `generation_reason` VARCHAR(255) DEFAULT NULL COMMENT '生成原因说明' AFTER `generation_trigger`,
    ADD COLUMN `expires_at` DATETIME DEFAULT NULL COMMENT '脚本有效期' AFTER `generation_reason`,
    ADD COLUMN `source_context_hash` VARCHAR(80) DEFAULT NULL COMMENT '源上下文摘要，用于判断是否需要刷新' AFTER `expires_at`,
    ADD COLUMN `error_message` VARCHAR(500) DEFAULT NULL COMMENT '生成失败原因' AFTER `source_context_hash`;

ALTER TABLE `lesson_bundle`
    ADD KEY `idx_expires_at` (`expires_at`);
