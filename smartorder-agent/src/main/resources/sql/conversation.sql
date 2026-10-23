-- ============================================================
-- 会话主表（AI 会话的一等实体）
-- ============================================================
-- 说明：
--   public_id 承载原有的 conversation_id（UUID 字符串），
--   conversation_message.conversation_id 与之对应，存量数据零改写。
--   内部 id 为雪花 ID（应用侧 IdUtil.getSnowflakeNextId() 生成），
--   只用于行锁（SELECT ... FOR UPDATE）与内部关联，不对外暴露。
-- ============================================================

CREATE TABLE IF NOT EXISTS `conversation` (
  `id`                   BIGINT       NOT NULL                COMMENT '内部主键（雪花ID，应用侧生成），仅用于锁行/关联，不对外',
  `public_id`            VARCHAR(36)  NOT NULL                COMMENT '对外会话ID（UUID），沿用原 conversation_id 值',
  `user_id`              BIGINT       NOT NULL                COMMENT '所属用户ID',
  `title`                VARCHAR(200) DEFAULT NULL            COMMENT '会话标题，默认取首条用户消息前30字',
  `title_source`         VARCHAR(16)  NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO=首条消息截断, LLM=模型总结, USER=用户改名',
  `status`               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=正常, ARCHIVED=归档, DELETED=已删除',
  `message_count`        INT          NOT NULL DEFAULT 0      COMMENT '可见消息数（user+assistant，不含 tool/system）',
  `last_message_at`      DATETIME(3)  DEFAULT NULL            COMMENT '最后一条可见消息时间（列表排序键）',
  `last_message_preview` VARCHAR(200) DEFAULT NULL            COMMENT '最后一条消息摘要（列表副标题，免回表）',
  `pinned`               TINYINT      NOT NULL DEFAULT 0      COMMENT '是否置顶',
  `pinned_at`            DATETIME(3)  DEFAULT NULL,
  `deleted_at`           DATETIME(3)  DEFAULT NULL,
  `created_at`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_public_id` (`public_id`),
  KEY `idx_user_status_time` (`user_id`, `status`, `pinned`, `last_message_at`),
  KEY `idx_status_time` (`status`, `last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 会话主表';
