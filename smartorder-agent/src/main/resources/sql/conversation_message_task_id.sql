-- ============================================================
-- conversation_message 关联对话任务
-- ============================================================
-- 一条 assistant 消息对应一个任务：task_id 与 agent_chat_task.task_id 使用同一个值。
-- 插入消息时由 ConversationMessageService 写入；历史数据允许为空。
-- ============================================================

ALTER TABLE `conversation_message`
    ADD COLUMN `task_id` VARCHAR(36) DEFAULT NULL COMMENT '对话任务ID；同一个 task 的 user/assistant 行共用' AFTER `trace_id`,
    ADD KEY `idx_conv_task` (`task_id`),
    ADD KEY `idx_conv_task_role` (`task_id`, `role`);
