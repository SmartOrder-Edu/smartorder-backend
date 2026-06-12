-- PlanAndExecute 架构：agent_plan_draft 表扩展
-- 新增 task_list_json、plan_type、resume_task_index 列

ALTER TABLE agent_plan_draft ADD COLUMN task_list_json TEXT COMMENT 'TaskList JSON (PlanAndExecute)' AFTER plan_json;
ALTER TABLE agent_plan_draft ADD COLUMN plan_type VARCHAR(20) DEFAULT 'LEGACY' COMMENT 'LEGACY or TASK_LIST' AFTER task_list_json;
ALTER TABLE agent_plan_draft ADD COLUMN resume_task_index INT DEFAULT 0 COMMENT 'Index of task to resume from' AFTER plan_type;

-- 更新已有数据的 plan_type
UPDATE agent_plan_draft SET plan_type = 'LEGACY' WHERE plan_type IS NULL;
