-- 评估题目表
CREATE TABLE IF NOT EXISTS `assessment_question` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '题目ID',
    `assessment_id`   BIGINT        NOT NULL                 COMMENT '所属评估ID',
    `question_type`   VARCHAR(20)   NOT NULL                 COMMENT '题目类型：mcq/short_answer/essay/coding/true_false/fill_blank',
    `question_text`   TEXT          NOT NULL                 COMMENT '题目文本/题干',
    `options`         JSON          DEFAULT NULL             COMMENT '选项（MCQ专用）',
    `correct_answer`  JSON          NOT NULL                 COMMENT '正确答案',
    `score`           DECIMAL(5,1)  NOT NULL DEFAULT 1.0     COMMENT '本题分值',
    `sort_order`      INT           NOT NULL DEFAULT 0       COMMENT '题目序号',
    `explanation`     TEXT          DEFAULT NULL             COMMENT '答案解析',
    `difficulty`      TINYINT       DEFAULT 1                COMMENT '难度：1=简单，5=困难',
    `is_deleted`      TINYINT       NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_assessment_id` (`assessment_id`),
    KEY `idx_assessment_sort` (`assessment_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估题目表';
