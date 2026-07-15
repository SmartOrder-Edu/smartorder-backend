-- 为练习分析报告增加异步生成状态字段。
ALTER TABLE `evaluation_learning_report`
    ADD COLUMN `status` varchar(32) NOT NULL DEFAULT 'generating' COMMENT '生成状态：waiting_grading/generating/ready/failed' AFTER `report_type`,
    ADD COLUMN `pending_count` int DEFAULT 0 COMMENT '待批阅题目数量' AFTER `status`,
    ADD COLUMN `error_message` text NULL COMMENT '生成失败原因' AFTER `pending_count`,
    ADD COLUMN `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试次数' AFTER `error_message`,
    ADD COLUMN `started_at` datetime NULL COMMENT '报告生成开始时间' AFTER `generated_by`,
    ADD COLUMN `finished_at` datetime NULL COMMENT '报告生成完成时间' AFTER `started_at`,
    ADD COLUMN `analysis_version` varchar(32) DEFAULT 'v1' COMMENT '分析算法/Prompt版本' AFTER `finished_at`;

CREATE UNIQUE INDEX `uk_session_report`
    ON `evaluation_learning_report` (`session_id`, `user_id`, `report_type`);
