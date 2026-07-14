-- 代码题测试用例表
-- 用于本地代码判题 MVP
CREATE TABLE evaluation_code_test_case (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '测试用例ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    input TEXT NULL COMMENT '标准输入',
    expected_output TEXT NOT NULL COMMENT '期望输出',
    is_sample TINYINT NOT NULL DEFAULT 0 COMMENT '是否样例：0否，1是',
    weight DECIMAL(6,2) NOT NULL DEFAULT 1.00 COMMENT '权重',
    time_limit_ms INT NOT NULL DEFAULT 1000 COMMENT '时间限制(ms)',
    memory_limit_mb INT NOT NULL DEFAULT 128 COMMENT '内存限制(MB)',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_question_id (question_id),
    INDEX idx_question_sample (question_id, is_sample),
    INDEX idx_question_status_sort (question_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码题测试用例表';
