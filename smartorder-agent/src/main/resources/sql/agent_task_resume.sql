-- 一次用户提交对应一条任务；唯一幂等键阻止重复提交重跑 Agent。
CREATE TABLE IF NOT EXISTS agent_chat_task (
  task_id VARCHAR(36) PRIMARY KEY COMMENT '任务及本轮 trace ID',
  message_id VARCHAR(36) NOT NULL COMMENT '本轮助手消息对外 ID，在确认时预分配',
  conversation_id VARCHAR(36) NOT NULL COMMENT '现有会话 public_id',
  user_id BIGINT NOT NULL COMMENT '任务所有者',
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '身份令牌存在租户时的租户标识',
  request_id VARCHAR(100) NOT NULL COMMENT '客户端稳定幂等键',
  request_hash CHAR(64) NOT NULL COMMENT '请求内容摘要',
  status VARCHAR(16) NOT NULL COMMENT 'QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED',
  stream_version VARCHAR(64) NOT NULL COMMENT 'Redis Stream 世代',
  stream_started TINYINT(1) NOT NULL DEFAULT 0 COMMENT '首次写入前已持久标记，防止丢流后重建',
  event_count BIGINT NOT NULL DEFAULT 0 COMMENT '成功写入 Redis 的事件数',
  snapshot_event_id VARCHAR(48) NOT NULL DEFAULT '0-0' COMMENT '消息投影已包含的事件游标',
  snapshot_revision BIGINT NOT NULL DEFAULT 0 COMMENT '单调快照版本',
  snapshot_content LONGTEXT NULL COMMENT '执行中已提交的回答增量投影',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  UNIQUE KEY uk_agent_chat_request (tenant_id, user_id, request_id),
  KEY idx_agent_chat_conversation (user_id, conversation_id, status)
) COMMENT='可恢复 Agent 对话任务';
