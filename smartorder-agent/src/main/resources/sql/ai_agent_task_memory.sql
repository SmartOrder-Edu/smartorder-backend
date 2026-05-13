CREATE TABLE ai_agent_task_memory (
                                      id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                      task_id BIGINT NOT NULL COMMENT '任务ID',
                                      user_id BIGINT NOT NULL COMMENT '用户ID',
                                      session_id BIGINT DEFAULT NULL COMMENT '会话ID',

                                      agent_id BIGINT NOT NULL COMMENT 'Agent ID',
                                      agent_role VARCHAR(100) NOT NULL COMMENT 'Agent角色，例如规划Agent、资源生成Agent、评估Agent、答疑Agent',

                                      task_type VARCHAR(100) NOT NULL COMMENT '任务类型：planning路径规划、generate_resource资源生成、evaluate学习评估、qa答疑、profile_update画像更新',
                                      task_goal TEXT NOT NULL COMMENT '任务目标',

                                      input_context JSON DEFAULT NULL COMMENT '任务输入上下文',
                                      process_trace JSON DEFAULT NULL COMMENT 'Agent执行过程记录',
                                      output_result JSON DEFAULT NULL COMMENT '任务输出结果',

                                      status TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态：0待执行，1执行中，2成功，3失败，4取消',
                                      error_message TEXT DEFAULT NULL COMMENT '错误信息',

                                      started_at DATETIME DEFAULT NULL COMMENT '开始时间',
                                      finished_at DATETIME DEFAULT NULL COMMENT '完成时间',

                                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                      INDEX idx_task_id (task_id),
                                      INDEX idx_user_id (user_id),
                                      INDEX idx_session_id (session_id),
                                      INDEX idx_agent_id (agent_id),
                                      INDEX idx_task_type (task_type),
                                      INDEX idx_status (status)
) COMMENT='多Agent任务记忆表';