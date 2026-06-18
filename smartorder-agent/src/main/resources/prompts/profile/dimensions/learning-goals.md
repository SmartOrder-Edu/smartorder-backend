维度：learning_goals

允许更新的字段结构：

```json
{
  "primary_goal_id": "主目标 ID",
  "goals": {
    "{goal_id}": {
      "goal_id": "目标唯一标识，如 GOAL-DATA_STRUCTURES-001",
      "scope": "GLOBAL | SUBJECT | KNOWLEDGE_POINT",
      "subject_id": "关联科目",
      "title": "目标标题",
      "description": "目标描述",
      "goal_type": "COURSE_MASTERY | EXAM | PROJECT | INTERVIEW",
      "target_level": "目标掌握等级",
      "measurable_criteria": [
        { "metric": "指标名", "operator": "比较符", "target_value": "目标值" }
      ],
      "priority": "HIGH | MEDIUM | LOW",
      "status": "DRAFT | ACTIVE | IN_PROGRESS | PAUSED | COMPLETED | CANCELLED | EXPIRED",
      "start_at": "开始时间 ISO 8601",
      "deadline": "截止时间 ISO 8601",
      "weekly_time_budget_minutes": "每周时间预算"
    }
  },
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.85,
    "completeness": 50,
    "source_types": ["USER_EXPLICIT"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "PROFILE_AGENT",
    "update_reason": "用户明确表达学习目标",
    "valid_until": null
  }
}
```

规则：目标必须来自用户明确表达，不要写入 progress 相关字段（由后端计算）。

### Metadata 填写要求

更新此维度时必须同时更新 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：用户表达越明确越高，模糊表达如"可能想学"给 0.7-0.8
- `completeness`：根据已有目标数量和字段完整度粗估
- `source_types`：目标通常来自 `["USER_EXPLICIT"]`
- `update_reason`：说明新增/修改/取消了哪个目标
- `valid_until`：填最远目标的截止时间，无截止时间填 `null`
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
