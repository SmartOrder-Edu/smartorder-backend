CREATE TABLE knowledge_files (
        id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件ID，系统内部唯一标识',

        user_id BIGINT NOT NULL COMMENT '所属用户ID',
        knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID',

        original_name VARCHAR(255) NOT NULL COMMENT '用户上传时的原始文件名',
        stored_name VARCHAR(255) COMMENT '系统存储时生成的文件名，用于避免文件重名',
        file_url VARCHAR(500) COMMENT '文件访问地址，用于前端预览或下载',

        file_extension VARCHAR(50) COMMENT '文件扩展名，例如：pdf、docx、pptx、txt、md',
        mime_type VARCHAR(100) COMMENT '文件MIME类型，例如：application/pdf',
        file_size BIGINT COMMENT '文件大小，单位为字节',
        file_hash VARCHAR(128) COMMENT '文件哈希值，用于去重和判断文件是否变化',

        parse_status VARCHAR(30) DEFAULT 'pending' COMMENT '解析状态：pending=待解析，parsing=解析中，parsed=解析完成，failed=解析失败',
        parse_error TEXT COMMENT '解析失败原因',

        chunk_status VARCHAR(30) DEFAULT 'pending' COMMENT '分块状态：pending=待分块，chunking=分块中，chunked=分块完成，failed=分块失败',
        chunk_error TEXT COMMENT '分块失败原因',

        embedding_status VARCHAR(30) DEFAULT 'pending' COMMENT '向量化状态：pending=待向量化，embedding=向量化中，embedded=向量化完成，failed=向量化失败',
        embedding_error TEXT COMMENT '向量化失败原因',

        page_count INT COMMENT '文件页数，主要用于PDF、PPT、Word等文档',
        word_count INT COMMENT '文件字数统计',
        chunk_count INT DEFAULT 0 COMMENT '该文件切分出的chunk数量',

        status VARCHAR(30) DEFAULT 'active' COMMENT '文件状态：active=正常，deleted=删除',

        created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

        INDEX idx_kf_user_id (user_id),
        INDEX idx_kf_kb_id (knowledge_base_id),
        INDEX idx_kf_hash (file_hash),
        INDEX idx_kf_parse_status (parse_status),
        INDEX idx_kf_embedding_status (embedding_status)
) COMMENT='知识库文件表，负责管理用户上传到知识库中的文件信息和处理状态';