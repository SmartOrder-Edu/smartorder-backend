CREATE TABLE `file_resource`
(
    `id`                 bigint NOT NULL AUTO_INCREMENT COMMENT '文件资源ID',
    `folder_id`          bigint                                                         DEFAULT NULL COMMENT '所属文件夹ID（0=根目录）',
    `user_id`            bigint                                                         DEFAULT NULL COMMENT '上传用户ID',
    `file_name`          varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  DEFAULT NULL COMMENT '文件名称--资源名称（可能被重命名）',
    `original_file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  DEFAULT NULL COMMENT '原始文件名',
    `file_url`           varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'OSS文件URL',
    `file_type`          varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci   DEFAULT NULL COMMENT '文件类型（pdf/docx/ppt/mp4/jpg/png等）',
    `file_size`          bigint                                                         DEFAULT NULL COMMENT '文件大小（字节）',
    `source`             bigint                                                         DEFAULT NULL COMMENT '来源：1-本地上传，2-AI生成归档，3-广场转存',
    `summary_content`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  DEFAULT NULL COMMENT 'AI解析后的摘要内容',
    `upload_status`      int                                                            DEFAULT NULL COMMENT '上传状态：0-上传中，1-上传成功，2-上传失败',
    `parse_status`       int                                                            DEFAULT NULL COMMENT '解析状态，0-未解析，1-解析中，2-向量化完成，3-解析失败',
    `create_time`        datetime                                                       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        datetime                                                       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY                  `idx_user_status` (`user_id`,`upload_status`),
    KEY                  `idx_folder_id` (`folder_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2056403360068255745 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件资源表';

CREATE TABLE `file_folder`
(
    `id`           bigint                                                        NOT NULL AUTO_INCREMENT COMMENT '文件夹ID',
    `parent_id`    bigint                                                        NOT NULL COMMENT '父文件夹ID（0=根目录）',
    `folder_name`  varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件夹名称',
    `user_id`      bigint   DEFAULT NULL COMMENT '所属用户ID',
    `sort`         int      DEFAULT '0' COMMENT '排序权重（越大越靠前，默认0）',
    `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY            `idx_user_parent` (`user_id`,`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2056376322343145473 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件夹表';

CREATE TABLE `user_file_quota`
(
    `user_id`    bigint NOT NULL COMMENT '用户ID',
    `user_space` bigint DEFAULT '0' COMMENT '已用空间(字节)',
    `version`    int    DEFAULT '0' COMMENT '乐观锁版本号',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;