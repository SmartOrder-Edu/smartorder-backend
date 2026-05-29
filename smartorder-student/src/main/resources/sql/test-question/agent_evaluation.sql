CREATE TABLE evaluation_question
(
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '题目ID',
    question_code        VARCHAR(64) NOT NULL COMMENT '题目唯一编码',
    learning_space_id    BIGINT      NOT NULL COMMENT '学习空间ID',
    chapter_id           BIGINT               DEFAULT NULL COMMENT '章节ID',
    knowledge_point_id   BIGINT               DEFAULT NULL COMMENT '知识点ID',
    question_type        VARCHAR(32) NOT NULL COMMENT '题型：single_choice/multiple_choice/judge/fill_blank/short_answer/code/scenario/sql/multimodal',
    difficulty           INT         NOT NULL DEFAULT 1 COMMENT '难度等级：1-10',
    score                DECIMAL(6, 2) NOT NULL DEFAULT 5.00 COMMENT '题目分值',
    ability_dimension    VARCHAR(64)          DEFAULT NULL COMMENT '能力维度：concept/application/analysis/design/debug',
    stem                 TEXT        NOT NULL COMMENT '题干',
    options_json         JSON                 DEFAULT NULL COMMENT '选项JSON',
    answer_json          JSON                 DEFAULT NULL COMMENT '标准答案JSON',
    explanation          TEXT COMMENT '题目解析',
    common_mistakes_json JSON                 DEFAULT NULL COMMENT '常见错误分析JSON',
    tags_json            JSON                 DEFAULT NULL COMMENT '标签JSON',
    generation_source    VARCHAR(32)          DEFAULT 'agent_generated' COMMENT '来源：agent_generated/manual/rag/imported',
    status               TINYINT              DEFAULT 1 COMMENT '状态：1启用，0禁用',
    created_at           DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_question_code (question_code),
    KEY                  idx_learning_space_id (learning_space_id),
    KEY                  idx_chapter_id (chapter_id),
    KEY                  idx_knowledge_point_id (knowledge_point_id),
    KEY                  idx_question_type (question_type)
) COMMENT='测评Agent题库表';


CREATE TABLE evaluation_session
(
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '测评任务ID',
    user_id                 BIGINT       NOT NULL COMMENT '用户ID',
    learning_space_id       BIGINT       NOT NULL COMMENT '学习空间ID',
    session_type            VARCHAR(32)  NOT NULL COMMENT '测评类型：diagnosis/chapter/weakness/stage/final/comprehensive',
    title                   VARCHAR(255) NOT NULL COMMENT '测评标题',
    chapter_id              BIGINT        DEFAULT NULL COMMENT '章节ID',
    target_knowledge_points JSON          DEFAULT NULL COMMENT '目标知识点列表',
    question_ids            JSON          DEFAULT NULL COMMENT '题目ID列表',
    difficulty_distribution JSON          DEFAULT NULL COMMENT '难度分布',
    ability_distribution    JSON          DEFAULT NULL COMMENT '能力维度分布',
    total_question_count    INT           DEFAULT 0 COMMENT '题目总数',
    answered_count          INT           DEFAULT 0 COMMENT '已作答数量',
    total_score             DECIMAL(6, 2) DEFAULT NULL COMMENT '总分',
    actual_score            DECIMAL(6, 2) DEFAULT NULL COMMENT '实际得分',
    accuracy                DECIMAL(5, 4) DEFAULT NULL COMMENT '正确率',
    status                  VARCHAR(32)   DEFAULT 'created' COMMENT '状态：created/doing/finished/cancelled',
    agent_strategy_json     JSON          DEFAULT NULL COMMENT '组卷策略JSON',
    started_at              DATETIME      DEFAULT NULL COMMENT '开始时间',
    finished_at             DATETIME      DEFAULT NULL COMMENT '完成时间',
    created_at              DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY                     idx_user_learning_space (user_id, learning_space_id),
    KEY                     idx_status (status),
    KEY                     idx_session_type (session_type)
) COMMENT='测评Agent测评任务表';


CREATE TABLE evaluation_answer_record
(
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '答题记录ID',
    session_id              BIGINT NOT NULL COMMENT '测评任务ID',
    user_id                 BIGINT NOT NULL COMMENT '用户ID',
    learning_space_id       BIGINT NOT NULL COMMENT '学习空间ID',
    question_id             BIGINT NOT NULL COMMENT '题目ID',
    knowledge_point_id      BIGINT        DEFAULT NULL COMMENT '知识点ID',
    user_answer             JSON          DEFAULT NULL COMMENT '用户答案JSON',
    standard_answer         JSON          DEFAULT NULL COMMENT '标准答案快照JSON',
    is_correct              TINYINT       DEFAULT NULL COMMENT '是否正确：1正确，0错误',
    score                   DECIMAL(6, 2) DEFAULT NULL COMMENT '得分',
    answer_duration_seconds INT           DEFAULT NULL COMMENT '作答耗时（秒）',
    used_hint               TINYINT       DEFAULT 0 COMMENT '是否使用提示：0否，1是',
    judge_result            JSON          DEFAULT NULL COMMENT '判题结果JSON',
    error_type              VARCHAR(64)   DEFAULT NULL COMMENT '错误类型',
    error_reason            TEXT COMMENT '错误原因',
    created_at              DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY                     idx_session_id (session_id),
    KEY                     idx_user_learning_space (user_id, learning_space_id),
    KEY                     idx_question_id (question_id),
    KEY                     idx_knowledge_point_id (knowledge_point_id)
) COMMENT='测评Agent答题记录表';


CREATE TABLE evaluation_student_knowledge
(
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id                  BIGINT NOT NULL COMMENT '用户ID',
    learning_space_id        BIGINT NOT NULL COMMENT '学习空间ID',
    knowledge_point_id       BIGINT NOT NULL COMMENT '知识点ID',
    mastery_score            DECIMAL(5, 4) DEFAULT 0.0000 COMMENT '掌握度，0-1',
    accuracy                 DECIMAL(5, 4) DEFAULT 0.0000 COMMENT '正确率，0-1',
    average_score            DECIMAL(6, 2) DEFAULT 0.00 COMMENT '平均得分',
    average_duration_seconds INT           DEFAULT NULL COMMENT '平均作答耗时',
    correct_count            INT           DEFAULT 0 COMMENT '答对次数',
    wrong_count              INT           DEFAULT 0 COMMENT '答错次数',
    weakness_level           INT           DEFAULT 0 COMMENT '薄弱等级：0正常，1轻微，2中等，3严重',
    last_session_id          BIGINT        DEFAULT NULL COMMENT '最近一次测评任务ID',
    last_evaluated_at        DATETIME      DEFAULT NULL COMMENT '最近评估时间',
    updated_at               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_space_kp (user_id, learning_space_id, knowledge_point_id),
    KEY                      idx_learning_space_id (learning_space_id),
    KEY                      idx_weakness_level (weakness_level)
) COMMENT='测评学生知识点评估表';


CREATE TABLE evaluation_student_error_analysis
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '错因分析ID',
    user_id            BIGINT      NOT NULL COMMENT '用户ID',
    learning_space_id  BIGINT      NOT NULL COMMENT '学习空间ID',
    session_id         BIGINT        DEFAULT NULL COMMENT '测评任务ID',
    question_id        BIGINT        DEFAULT NULL COMMENT '题目ID',
    knowledge_point_id BIGINT        DEFAULT NULL COMMENT '知识点ID',
    error_type         VARCHAR(64) NOT NULL COMMENT '错误类型：concept_missing/calculation_error/confused_concept/incomplete_answer/logic_error/code_bug',
    error_reason       TEXT COMMENT '错误原因',
    evidence           TEXT COMMENT '判断依据',
    suggestion         TEXT COMMENT '改进建议',
    confidence         DECIMAL(5, 4) DEFAULT 0.0000 COMMENT '分析置信度，0-1',
    created_at         DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY                idx_user_learning_space (user_id, learning_space_id),
    KEY                idx_session_id (session_id),
    KEY                idx_question_id (question_id),
    KEY                idx_knowledge_point_id (knowledge_point_id),
    KEY                idx_error_type (error_type)
) COMMENT='测评学生错因分析表';


CREATE TABLE evaluation_learning_report
(
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '评测报告ID',
    user_id                  BIGINT      NOT NULL COMMENT '用户ID',
    learning_space_id        BIGINT      NOT NULL COMMENT '学习空间ID',
    session_id               BIGINT        DEFAULT NULL COMMENT '测评任务ID',
    report_type              VARCHAR(32) NOT NULL COMMENT '报告类型：single_test/chapter/stage/final/comprehensive',
    total_score              DECIMAL(6, 2) DEFAULT NULL COMMENT '总分',
    actual_score             DECIMAL(6, 2) DEFAULT NULL COMMENT '实际得分',
    accuracy                 DECIMAL(5, 4) DEFAULT NULL COMMENT '正确率',
    mastery_summary          JSON          DEFAULT NULL COMMENT '知识点掌握概览',
    weak_points              JSON          DEFAULT NULL COMMENT '薄弱点列表',
    strong_points            JSON          DEFAULT NULL COMMENT '优势点列表',
    ability_evaluation       JSON          DEFAULT NULL COMMENT '能力维度评估',
    learning_suggestions     JSON          DEFAULT NULL COMMENT '学习建议',
    next_recommendation_json JSON          DEFAULT NULL COMMENT '下一步推荐JSON',
    report_content           TEXT COMMENT '自然语言报告内容',
    generated_by             VARCHAR(32)   DEFAULT 'agent' COMMENT '生成方式：agent/system/manual',
    created_at               DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY                      idx_user_learning_space (user_id, learning_space_id),
    KEY                      idx_session_id (session_id),
    KEY                      idx_report_type (report_type)
) COMMENT='测评学习评测报告表';

CREATE TABLE evaluation_question_rubric
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '评分规则ID',
    question_id BIGINT      NOT NULL COMMENT '题目ID',
    rubric_json JSON        NOT NULL COMMENT '评分规则JSON',
    total_score DECIMAL(6, 2) DEFAULT 100.00 COMMENT '满分',
    judge_mode  VARCHAR(32) NOT NULL COMMENT '判题方式：exact_match/llm/sql_execute/code_execute/rubric',
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_question_id (question_id)
) COMMENT='测评题目评分规则表';
