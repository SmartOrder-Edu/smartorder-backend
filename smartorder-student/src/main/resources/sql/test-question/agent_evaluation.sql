SET NAMES utf8mb4;
SET
FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 表结构：evaluation_answer_record（测评Agent答题记录表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_answer_record`;
CREATE TABLE `evaluation_answer_record`
(
    `id`                 bigint NOT NULL AUTO_INCREMENT COMMENT '答题记录ID',
    `session_id`         bigint                                                       DEFAULT NULL COMMENT '测评任务ID',
    `user_id`            bigint NOT NULL COMMENT '用户ID',
    `learning_space_id`  bigint NOT NULL COMMENT '学习空间ID',
    `knowledge_point_id` bigint                                                       DEFAULT NULL COMMENT '知识点ID',
    `question_id`        bigint NOT NULL COMMENT '题目ID',
    `user_answer`        json                                                         DEFAULT NULL COMMENT '用户答案JSON',
    `correct_answer`     json                                                         DEFAULT NULL COMMENT '标准答案快照JSON',
    `is_correct`         tinyint                                                      DEFAULT NULL COMMENT '是否正确：1正确，0错误',
    `score`              decimal(6, 2)                                                DEFAULT NULL COMMENT '得分',
    `duration`           int                                                          DEFAULT NULL COMMENT '作答耗时（秒）',
    `used_hint`          tinyint                                                      DEFAULT 0 COMMENT '是否使用提示：0否，1是',
    `judge_result`       json                                                         DEFAULT NULL COMMENT '判题结果JSON',
    `error_type`         varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '错误类型',
    `error_reason`       text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '错误原因',
    `created_at`         datetime                                                     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime                                                     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX                `idx_session_id` (`session_id` ASC) USING BTREE,
    INDEX                `idx_user_learning_space` (`user_id` ASC, `learning_space_id` ASC) USING BTREE,
    INDEX                `idx_question_id` (`question_id` ASC) USING BTREE,
    INDEX                `idx_knowledge_point_id` (`knowledge_point_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '测评Agent答题记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_learning_report（测评学习评估报告表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_learning_report`;
CREATE TABLE `evaluation_learning_report`
(
    `id`                       bigint                                                       NOT NULL AUTO_INCREMENT COMMENT '评估报告ID',
    `user_id`                  bigint                                                       NOT NULL COMMENT '用户ID',
    `learning_space_id`        bigint                                                       NOT NULL COMMENT '学习空间ID',
    `session_id`               bigint                                                       DEFAULT NULL COMMENT '测评任务ID',
    `report_type`              varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '报告类型：single_test/chapter/stage/final/comprehensive',
    `total_score`              decimal(6, 2)                                                DEFAULT NULL COMMENT '总分',
    `actual_score`             decimal(6, 2)                                                DEFAULT NULL COMMENT '实际得分',
    `accuracy`                 decimal(5, 4)                                                DEFAULT NULL COMMENT '正确率',
    `mastery_summary`          json                                                         DEFAULT NULL COMMENT '知识点掌握概览',
    `weak_points`              json                                                         DEFAULT NULL COMMENT '薄弱点列表',
    `strong_points`            json                                                         DEFAULT NULL COMMENT '优势点列表',
    `ability_evaluation`       json                                                         DEFAULT NULL COMMENT '能力维度评估',
    `learning_suggestions`     json                                                         DEFAULT NULL COMMENT '学习建议',
    `next_recommendation_json` json                                                         DEFAULT NULL COMMENT '下一步推荐JSON',
    `report_content`           text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '自然语言报告内容',
    `generated_by`             varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'agent' COMMENT '生成方式：agent/system/manual',
    `created_at`               datetime                                                     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`               datetime                                                     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX                      `idx_user_learning_space` (`user_id` ASC, `learning_space_id` ASC) USING BTREE,
    INDEX                      `idx_session_id` (`session_id` ASC) USING BTREE,
    INDEX                      `idx_report_type` (`report_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '测评学习评估报告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_question（测评Agent题库表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_question`;
CREATE TABLE `evaluation_question`
(
    `id`                    bigint                                                       NOT NULL AUTO_INCREMENT COMMENT '题目ID',
    `question_code`         varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci          DEFAULT NULL COMMENT '题目唯一编码',
    `learning_space_id`     bigint                                                       NOT NULL COMMENT '学习空间ID',
    `chapter_id`            bigint                                                                DEFAULT 0 COMMENT '章节ID',
    `knowledge_point_id`    bigint                                                                DEFAULT 0 COMMENT '知识点ID',
    `question_type`         varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题型：single_choice/multiple_choice/judge/fill_blank/short_answer/code/scenario/sql/multimodal',
    `difficulty`            int                                                          NOT NULL DEFAULT 1 COMMENT '难度等级：1-5',
    `score`                 decimal(6, 2)                                                NOT NULL DEFAULT 5.00 COMMENT '题目分值',
    `ability_dimension`     varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci          DEFAULT NULL COMMENT '能力维度：concept/application/analysis/design/debug',
    `stem`                  text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题干',
    `options_json`          json                                                                  DEFAULT NULL COMMENT '选项JSON',
    `answer_json`           json                                                                  DEFAULT NULL COMMENT '标准答案JSON',
    `explanation`           text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '题目解析',
    `common_mistakes_json`  json                                                                  DEFAULT NULL COMMENT '常见错误分析JSON',
    `analysis_json`         json                                                                  DEFAULT NULL COMMENT '题目分析JSON（包含correct_reason和wrong_reason）',
    `tags_json`             json                                                                  DEFAULT NULL COMMENT '标签JSON',
    `knowledge_points_json` json                                                                  DEFAULT NULL COMMENT '知识点标签JSON',
    `ability_tags_json`     json                                                                  DEFAULT NULL COMMENT '能力标签JSON',
    `generation_source`     varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci          DEFAULT 'agent_generated' COMMENT '来源：agent_generated/manual/rag/imported',
    `status`                tinyint                                                               DEFAULT 1 COMMENT '状态：1启用，0禁用',
    `created_at`            datetime                                                              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            datetime                                                              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX                   `idx_learning_space_id` (`learning_space_id` ASC) USING BTREE,
    INDEX                   `idx_chapter_id` (`chapter_id` ASC) USING BTREE,
    INDEX                   `idx_knowledge_point_id` (`knowledge_point_id` ASC) USING BTREE,
    INDEX                   `idx_question_type` (`question_type` ASC) USING BTREE,
    INDEX                   `uk_question_code` (`question_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 47721852000002 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '测评Agent题库表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_question_generation_item（题库单题生成子任务表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_question_generation_item`;
CREATE TABLE `evaluation_question_generation_item`
(
    `id`              bigint                                                       NOT NULL AUTO_INCREMENT COMMENT '单题生成子任务ID',
    `task_id`         bigint                                                       NOT NULL COMMENT '所属生成任务ID',
    `question_id`     bigint                                                                DEFAULT NULL COMMENT '生成成功后的题目ID',
    `question_type`   varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题型',
    `difficulty`      int                                                          NOT NULL DEFAULT 5 COMMENT '难度',
    `option_count`    int                                                                   DEFAULT 4 COMMENT '选项数量',
    `sequence_no`     int                                                          NOT NULL COMMENT '任务内顺序',
    `status`          varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'pending' COMMENT '状态：pending/running/success/failed',
    `retry_count`     int                                                          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry_count` int                                                          NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `raw_response`    mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '模型原始返回',
    `error_message`   text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '失败原因',
    `started_at`      datetime                                                              DEFAULT NULL COMMENT '开始时间',
    `finished_at`     datetime                                                              DEFAULT NULL COMMENT '完成时间',
    `created_at`      datetime                                                              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      datetime                                                              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_job_sequence` (`task_id` ASC, `sequence_no` ASC) USING BTREE,
    INDEX             `idx_job_status` (`task_id` ASC, `status` ASC) USING BTREE,
    INDEX             `idx_question_id` (`question_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 47721810000015 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题库单题生成子任务表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_question_generation_task（题库生成任务表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_question_generation_task`;
CREATE TABLE `evaluation_question_generation_task`
(
    `id`                 bigint                                                       NOT NULL AUTO_INCREMENT COMMENT '题库生成任务ID',
    `user_id`            bigint                                                       NOT NULL COMMENT '用户ID',
    `learning_space_id`  bigint                                                       NOT NULL COMMENT '学习空间ID',
    `chapter_id`         bigint                                                                DEFAULT NULL COMMENT '章节ID',
    `knowledge_point_id` bigint                                                                DEFAULT NULL COMMENT '知识点ID',
    `course_name`        varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci         DEFAULT NULL COMMENT '课程名称',
    `topic`              varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci         DEFAULT NULL COMMENT '出题主题',
    `request_payload`    json                                                         NOT NULL COMMENT '原始生成参数',
    `status`             varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'pending' COMMENT '状态：pending/running/success/partial_success/failed/cancelled',
    `total_count`        int                                                          NOT NULL DEFAULT 0 COMMENT '总题数',
    `success_count`      int                                                          NOT NULL DEFAULT 0 COMMENT '成功题数',
    `failed_count`       int                                                          NOT NULL DEFAULT 0 COMMENT '失败题数',
    `progress`           int                                                          NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    `current_step`       varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci         DEFAULT NULL COMMENT '当前步骤',
    `error_message`      text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '任务错误信息',
    `started_at`         datetime                                                              DEFAULT NULL COMMENT '开始时间',
    `finished_at`        datetime                                                              DEFAULT NULL COMMENT '完成时间',
    `created_at`         datetime                                                              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime                                                              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX                `idx_user_status` (`user_id` ASC, `status` ASC) USING BTREE,
    INDEX                `idx_space_status` (`learning_space_id` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 47721810000001 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题库生成任务表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_session（测评Agent测评任务表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_session`;
CREATE TABLE `evaluation_session`
(
    `id`                      bigint                                                        NOT NULL AUTO_INCREMENT COMMENT '测评任务ID',
    `user_id`                 bigint                                                        NOT NULL COMMENT '用户ID',
    `learning_space_id`       bigint                                                        NOT NULL COMMENT '学习空间ID',
    `session_type`            varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '测评类型：diagnosis/chapter/weakness/stage/final/comprehensive',
    `title`                   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '测评标题',
    `chapter_id`              bigint                                                       DEFAULT NULL COMMENT '章节ID',
    `target_knowledge_points` json                                                         DEFAULT NULL COMMENT '目标知识点列表',
    `question_ids`            json                                                         DEFAULT NULL COMMENT '题目ID列表',
    `difficulty_distribution` json                                                         DEFAULT NULL COMMENT '难度分布',
    `ability_distribution`    json                                                         DEFAULT NULL COMMENT '能力维度分布',
    `total_question_count`    int                                                          DEFAULT 0 COMMENT '题目总数',
    `answered_count`          int                                                          DEFAULT 0 COMMENT '已作答数量',
    `total_score`             decimal(5, 2)                                                DEFAULT NULL COMMENT '总分',
    `actual_score`            decimal(5, 2)                                                DEFAULT NULL COMMENT '实际得分',
    `accuracy`                decimal(5, 2)                                                DEFAULT NULL COMMENT '正确率',
    `status`                  varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'created' COMMENT '状态：created/doing/finished/cancelled',
    `agent_strategy_json`     json                                                         DEFAULT NULL COMMENT '组卷策略JSON',
    `started_at`              datetime                                                     DEFAULT NULL COMMENT '开始时间',
    `finished_at`             datetime                                                     DEFAULT NULL COMMENT '完成时间',
    `created_at`              datetime                                                     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`              datetime                                                     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX                     `idx_user_learning_space` (`user_id` ASC, `learning_space_id` ASC) USING BTREE,
    INDEX                     `idx_status` (`status` ASC) USING BTREE,
    INDEX                     `idx_session_type` (`session_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '测评Agent测评任务表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_student_error_analysis（测评学生错因分析表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_student_error_analysis`;
CREATE TABLE `evaluation_student_error_analysis`
(
    `id`                 bigint                                                       NOT NULL AUTO_INCREMENT COMMENT '错因分析ID',
    `user_id`            bigint                                                       NOT NULL COMMENT '用户ID',
    `learning_space_id`  bigint                                                       NOT NULL COMMENT '学习空间ID',
    `session_id`         bigint        DEFAULT NULL COMMENT '测评任务ID',
    `question_id`        bigint        DEFAULT NULL COMMENT '题目ID',
    `knowledge_point_id` bigint        DEFAULT NULL COMMENT '知识点ID',
    `error_type`         varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '错误类型：concept_missing/calculation_error/confused_concept/incomplete_answer/logic_error/code_bug',
    `error_reason`       text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '错误原因',
    `evidence`           text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '判断依据',
    `suggestion`         text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '改进建议',
    `confidence`         decimal(5, 4) DEFAULT 0.0000 COMMENT '分析置信度，0-1',
    `created_at`         datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX                `idx_user_learning_space` (`user_id` ASC, `learning_space_id` ASC) USING BTREE,
    INDEX                `idx_session_id` (`session_id` ASC) USING BTREE,
    INDEX                `idx_question_id` (`question_id` ASC) USING BTREE,
    INDEX                `idx_knowledge_point_id` (`knowledge_point_id` ASC) USING BTREE,
    INDEX                `idx_error_type` (`error_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '测评学生错因分析表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 表结构：evaluation_student_knowledge（测评学生知识点评估表）
-- ----------------------------
DROP TABLE IF EXISTS `evaluation_student_knowledge`;
CREATE TABLE `evaluation_student_knowledge`
(
    `id`                       bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`                  bigint NOT NULL COMMENT '用户ID',
    `learning_space_id`        bigint NOT NULL COMMENT '学习空间ID',
    `knowledge_point_id`       bigint NOT NULL COMMENT '知识点ID',
    `mastery_score`            decimal(5, 4) DEFAULT 0.0000 COMMENT '掌握度，0-1',
    `accuracy`                 decimal(5, 4) DEFAULT 0.0000 COMMENT '正确率，0-1',
    `average_score`            decimal(6, 2) DEFAULT 0.00 COMMENT '平均得分',
    `average_duration_seconds` int           DEFAULT NULL COMMENT '平均作答耗时',
    `correct_count`            int           DEFAULT 0 COMMENT '答对次数',
    `wrong_count`              int           DEFAULT 0 COMMENT '答错次数',
    `weakness_level`           int           DEFAULT 0 COMMENT '薄弱等级：0正常，1轻微，2中等，3严重',
    `last_session_id`          bigint        DEFAULT NULL COMMENT '最近一次测评任务ID',
    `last_evaluated_at`        datetime      DEFAULT NULL COMMENT '最近评估时间',
    `updated_at`               datetime      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_user_space_kp` (`user_id` ASC, `learning_space_id` ASC, `knowledge_point_id` ASC) USING BTREE,
    INDEX                      `idx_learning_space_id` (`learning_space_id` ASC) USING BTREE,
    INDEX                      `idx_weakness_level` (`weakness_level` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '测评学生知识点评估表' ROW_FORMAT = Dynamic;

SET
FOREIGN_KEY_CHECKS = 1;