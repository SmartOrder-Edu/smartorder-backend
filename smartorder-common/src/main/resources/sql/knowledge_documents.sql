CREATE TABLE knowledge_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文档ID，系统内部唯一标识',

    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID',
    file_id BIGINT NOT NULL COMMENT '来源文件ID，表示该文档由哪个文件解析得到',

    title VARCHAR(255) COMMENT '文档标题，通常来自文件名、PDF标题或用户手动填写',
    source_type VARCHAR(50) DEFAULT 'file' COMMENT '来源类型：file=文件上传，url=网页导入，manual=手动创建，note=笔记',

    raw_text_path VARCHAR(500) COMMENT '解析后完整文本的存储路径，适合将大文本存到对象存储',
    summary TEXT COMMENT '文档摘要，可由AI生成，用于前端预览',
    language VARCHAR(50) COMMENT '文档语言，例如：zh-CN、en-US',

    page_count INT COMMENT '文档页数',
    word_count INT COMMENT '文档字数',
    chunk_count INT DEFAULT 0 COMMENT '文档切分后的chunk数量',

    metadata JSON COMMENT '文档元信息，例如作者、页数、章节目录、解析器版本、原始格式信息',

    version INT DEFAULT 1 COMMENT '文档版本号，文件重新解析或更新时递增',
    status VARCHAR(30) DEFAULT 'active' COMMENT '文档状态：active=正常，deleted=删除',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_kd_user_id (user_id),
    INDEX idx_kd_kb_id (knowledge_base_id),
    INDEX idx_kd_file_id (file_id)
) COMMENT='知识文档表，负责管理文件解析后的文档信息';