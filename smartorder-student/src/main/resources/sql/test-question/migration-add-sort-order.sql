-- =============================================
-- 迁移：evaluation_question 表增加 sort_order 字段
-- 用途：控制题目在同题型内的展示顺序，前端据此排序
-- 执行时间：2026-07-10
-- =============================================

ALTER TABLE `evaluation_question`
    ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序序号（同题型内排序，数值越小越靠前）' AFTER `question_type`;

-- 为已有数据初始化 sort_order：按 learning_space_id + question_type 分组，按 id 升序编号
UPDATE `evaluation_question` eq
    JOIN (
    SELECT id,
    ROW_NUMBER() OVER (PARTITION BY learning_space_id, question_type ORDER BY id ASC) AS rn
    FROM `evaluation_question`
    ) ranked ON eq.id = ranked.id
    SET eq.sort_order = ranked.rn;

-- 加索引加速排序查询
ALTER TABLE `evaluation_question`
    ADD INDEX `idx_space_type_sort` (`learning_space_id`, `question_type`, `sort_order`);
