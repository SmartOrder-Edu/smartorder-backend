CREATE TABLE knowledge_bases (
     id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识库ID，系统内部唯一标识',

     user_id BIGINT NOT NULL COMMENT '所属用户ID，表示该知识库属于哪个用户',

     name VARCHAR(200) NOT NULL COMMENT '知识库名称，例如：操作系统知识库、英语四级知识库',
     description TEXT COMMENT '知识库描述，用于说明该知识库的内容范围和用途',

     category VARCHAR(100) COMMENT '知识库分类，例如：课程学习、项目资料、论文阅读、个人笔记',
     cover_url VARCHAR(500) COMMENT '知识库封面图地址，用于前端展示',
     icon VARCHAR(100) COMMENT '知识库图标标识，用于前端展示',
     color VARCHAR(50) COMMENT '知识库主题色，用于前端展示',

     embedding_model VARCHAR(100) COMMENT '该知识库默认使用的向量模型名称，例如：bge-m3、text-embedding-3-small',
     chunk_strategy VARCHAR(50) DEFAULT 'recursive' COMMENT '默认分块策略：recursive=递归分块，markdown=按标题分块，sentence=按句子分块',

     retrieval_top_k INT DEFAULT 5 COMMENT '默认召回数量，表示RAG检索时默认返回多少个chunk',
     score_threshold DECIMAL(6,4) DEFAULT 0.3000 COMMENT '默认相似度阈值，低于该分数的chunk不参与回答',

     file_count INT DEFAULT 0 COMMENT '知识库下文件数量，冗余字段，用于前端快速展示',
     document_count INT DEFAULT 0 COMMENT '知识库下文档数量，冗余字段，用于前端快速展示',
     chunk_count INT DEFAULT 0 COMMENT '知识库下chunk数量，冗余字段，用于前端快速展示',

     total_size BIGINT DEFAULT 0 COMMENT '知识库下所有文件总大小，单位为字节',

     last_used_at DATETIME COMMENT '知识库最近一次被检索或使用的时间',
     last_updated_at DATETIME COMMENT '知识库内容最近更新时间，例如新增文件、删除文件、重新解析',

     status VARCHAR(30) DEFAULT 'active' COMMENT '知识库状态：active=正常，archived=归档，deleted=删除',

     created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT='知识库基本信息表，负责知识库列表展示和基础配置管理';