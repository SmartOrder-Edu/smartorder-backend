-- Algorithm evidence and question knowledge mapping tables.
-- The LLM may suggest semantic mappings, but deterministic algorithms consume
-- only persisted mappings and graded answer facts.

CREATE TABLE IF NOT EXISTS `evaluation_question_knowledge_node` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'Mapping ID',
    `question_id`       BIGINT        NOT NULL                COMMENT 'Evaluation question ID',
    `knowledge_node_id` BIGINT        NOT NULL                COMMENT 'Knowledge graph node ID',
    `weight`            DECIMAL(5,4)  NOT NULL DEFAULT 1.0000 COMMENT 'Contribution weight for this node',
    `source`            VARCHAR(40)   NOT NULL DEFAULT 'manual' COMMENT 'manual/tag_match/llm_suggested/imported',
    `confidence`        DECIMAL(5,4)  NOT NULL DEFAULT 1.0000 COMMENT 'Mapping confidence',
    `reason`            VARCHAR(1000) DEFAULT NULL            COMMENT 'Mapping rationale',
    `is_deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT 'Logical delete',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_node` (`question_id`, `knowledge_node_id`, `is_deleted`),
    KEY `idx_question_id` (`question_id`),
    KEY `idx_knowledge_node_id` (`knowledge_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Evaluation question to knowledge node mapping';

CREATE TABLE IF NOT EXISTS `learning_evidence_event` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'Evidence event ID',
    `user_id`             BIGINT        NOT NULL                COMMENT 'User ID',
    `space_id`            BIGINT        NOT NULL                COMMENT 'Learning space ID',
    `source_type`         VARCHAR(50)   NOT NULL                COMMENT 'evaluation_answer_record/assessment_answer/study_event/task',
    `source_id`           BIGINT        NOT NULL                COMMENT 'Source record ID',
    `question_id`         BIGINT        DEFAULT NULL            COMMENT 'Question ID',
    `knowledge_node_id`   BIGINT        NOT NULL                COMMENT 'Knowledge node ID',
    `score`               DECIMAL(6,4)  NOT NULL DEFAULT 0.0000 COMMENT 'Normalized score 0-1',
    `is_correct`          TINYINT       DEFAULT NULL            COMMENT 'Whether the answer is correct',
    `difficulty`          TINYINT       DEFAULT NULL            COMMENT 'Question difficulty 1-5',
    `time_spent_seconds`  INT           DEFAULT NULL            COMMENT 'Time spent in seconds',
    `expected_seconds`    INT           DEFAULT NULL            COMMENT 'Expected time in seconds',
    `hint_count`          INT           NOT NULL DEFAULT 0      COMMENT 'Hint count',
    `evidence_weight`     DECIMAL(8,4)  NOT NULL DEFAULT 1.0000 COMMENT 'Computed evidence weight',
    `error_type`          VARCHAR(64)   DEFAULT NULL            COMMENT 'Normalized error type',
    `semantic_confidence` DECIMAL(5,4)  DEFAULT NULL            COMMENT 'LLM semantic confidence, if used',
    `payload`             JSON          DEFAULT NULL            COMMENT 'Trace payload',
    `is_deleted`          TINYINT       NOT NULL DEFAULT 0      COMMENT 'Logical delete',
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_node` (`source_type`, `source_id`, `knowledge_node_id`, `is_deleted`),
    KEY `idx_user_space_node` (`user_id`, `space_id`, `knowledge_node_id`),
    KEY `idx_question_id` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning algorithm evidence event';
