-- ============================================================
-- 新对话草稿与 conversation_uuid 的一对一绑定
-- ============================================================
-- 为什么需要独立表：
--   前端每次点击“新建对话”生成一个 draft_id，并同时生成 conversation_uuid；
--   draft_id ↔ conversation_uuid 必须严格一对一。
--   同一 draft 重试 → 命中同一 conversation_uuid，幂等；
--   新 draft 复用旧 conversation_uuid → 命中 uk_draft_conversation_uuid 冲突；
--   同一 draft 换 conversation_uuid → 命中主键/字段比较冲突。
--
-- 存储策略：
--   MySQL 是权威（唯一键最终防线）；
--   Redis 缓存两条方向的映射（draft → conversation、conversation → draft），
--   只做快速失败，缓存丢失时回源 MySQL。
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent_conversation_draft` (
    `draft_id`          VARCHAR(36)  NOT NULL                COMMENT '前端生成的新对话草稿ID（每次“新建对话”一个）',
    `conversation_uuid` VARCHAR(36)  NOT NULL                COMMENT '该草稿绑定的 conversation_uuid，一对一',
    `user_id`           BIGINT       NOT NULL                COMMENT '所属用户',
    `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`draft_id`),
    UNIQUE KEY `uk_draft_conversation_uuid` (`conversation_uuid`),
    KEY `idx_draft_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='新对话草稿与 conversation_uuid 的一对一绑定';
