CREATE TABLE `feedback` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `type` tinyint(4) NOT NULL COMMENT '反馈类型：1=体验评价，2=功能建议，3=Bug反馈，4=其他意见',
  `title` varchar(100) DEFAULT NULL COMMENT '反馈标题',
  `content` text NOT NULL COMMENT '反馈内容',
  `image_urls` varchar(1024) DEFAULT NULL COMMENT '图片URLs',
  `contact` varchar(100) DEFAULT NULL COMMENT '联系方式（手机/微信）',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '审核状态：0=待AI初审，1=待人工复核，2=已被AI拦截，3=已录入(有效)，4=已被驳回，5=已被采纳',
  `ai_analysis_result` text COMMENT 'AI初审结果JSON',
  `review_remark` varchar(255) DEFAULT NULL COMMENT '驳回理由或备注',
  `reply_content` text DEFAULT NULL COMMENT '管理员答复内容',
  `reply_time` datetime DEFAULT NULL COMMENT '管理员答复时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否逻辑删除：0=未删除，1=已删除',
  PRIMARY KEY (`id`),
  KEY `idx_user_deleted_time` (`user_id`, `is_deleted`, `create_time` DESC) COMMENT '优化C端用户历史反馈高频分页联合索引',
  KEY `idx_status_deleted_time` (`status`, `is_deleted`, `create_time` DESC) COMMENT '优化B端审核列表多维过滤及看板统计索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户评价反馈表';

CREATE TABLE `prize` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '奖品ID',
  `name` varchar(100) NOT NULL COMMENT '奖品名称',
  `prize_code` varchar(100) NOT NULL COMMENT '奖品兑换码',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态：0=未发放，1=已发放，2=已废弃',
  `user_id` bigint(20) DEFAULT NULL COMMENT '获得该奖品的用户ID',
  `feedback_id` bigint(20) DEFAULT NULL COMMENT '关联的反馈记录ID',
  `allocate_time` datetime DEFAULT NULL COMMENT '发放时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prize_code` (`prize_code`),
  KEY `idx_status_name` (`status`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反馈奖品卡密库表';
