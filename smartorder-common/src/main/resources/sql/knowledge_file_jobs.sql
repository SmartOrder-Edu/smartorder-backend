CREATE TABLE knowledge_file_jobs (
     id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件处理任务ID',

     user_id BIGINT NOT NULL COMMENT '所属用户ID',
     knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID',
     file_id BIGINT NOT NULL COMMENT '需要处理的文件ID',

     job_type VARCHAR(50) DEFAULT 'parse_chunk_embed' COMMENT '任务类型：parse=解析，chunk=分块，embed=向量化，parse_chunk_embed=解析分块并向量化',

     status VARCHAR(30) DEFAULT 'pending' COMMENT '任务状态：pending=等待执行，running=执行中，success=成功，failed=失败',

     progress INT DEFAULT 0 COMMENT '任务进度，范围0到100',
     current_step VARCHAR(100) COMMENT '当前处理步骤，例如：解析中、分块中、向量化中',

     error_message TEXT COMMENT '任务失败时的错误信息',

     started_at DATETIME COMMENT '任务开始时间',
     finished_at DATETIME COMMENT '任务结束时间',

     created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

     INDEX idx_kfj_user_id (user_id),
     INDEX idx_kfj_kb_id (knowledge_base_id),
     INDEX idx_kfj_file_id (file_id),
     INDEX idx_kfj_status (status)
) COMMENT='知识库文件处理任务表，用于异步处理文件解析、分块和向量化';