-- 已部署旧版 agent_chat_task 时执行一次；新环境直接使用 agent_task_resume.sql。
ALTER TABLE agent_chat_task
  ADD COLUMN message_id VARCHAR(36) NULL COMMENT '本轮助手消息对外 ID，在确认时预分配' AFTER task_id;

-- 优先使用已生成的助手消息 public_id，历史和恢复快照才会指向同一条消息。
UPDATE agent_chat_task t
JOIN conversation_message m ON m.trace_id = t.task_id AND m.role = 'assistant'
SET t.message_id = m.public_id
WHERE t.message_id IS NULL AND m.public_id IS NOT NULL;

-- 尚未开轮的旧任务沿用此前确认响应的 task_id。
UPDATE agent_chat_task SET message_id = task_id WHERE message_id IS NULL;

ALTER TABLE agent_chat_task MODIFY COLUMN message_id VARCHAR(36) NOT NULL
  COMMENT '本轮助手消息对外 ID，在确认时预分配';
