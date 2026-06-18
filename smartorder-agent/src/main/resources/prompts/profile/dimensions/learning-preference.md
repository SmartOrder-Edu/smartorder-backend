维度：learning_preference

允许更新的字段结构：

```json
{
  "resource_preferences": {
    "{type}": {
      "declared_preference": "HIGH | MEDIUM | LOW"
    }
  },
  "explanation_preferences": {
    "preferred_sequence": "EXAMPLE_THEN_PRINCIPLE | PRINCIPLE_THEN_EXAMPLE",
    "content_granularity": "FINE | MEDIUM | COARSE",
    "preferred_representation": ["STATE_DIAGRAM", "CODE_TRACE", "PSEUDOCODE"],
    "preferred_language_style": "语言风格",
    "analogy_preference": "LOW | MODERATE | HIGH"
  },
  "practice_preferences": {
    "practice_timing": "练习时机",
    "preferred_question_types": ["偏好题型"],
    "preferred_difficulty_progression": "GRADUAL | ADAPTIVE | CHALLENGING",
    "preferred_questions_per_session": "每次练习题数",
    "allow_hints": true,
    "preferred_hint_style": "提示风格"
  },
  "feedback_preferences": {
    "feedback_timing": "IMMEDIATE | DELAYED",
    "feedback_detail_level": "BRIEF | DETAILED",
    "show_error_cause": true,
    "show_improvement_suggestion": true
  },
  "interaction_preferences": {
    "preferred_interaction_mode": "交互模式",
    "allow_agent_proactive_reminder": true,
    "preferred_questioning_style": "提问风格"
  },
  "subject_overrides": {
    "{subject_id}": {
      "preferred_resource_types": ["偏好资源类型"],
      "preferred_explanation_sequence": "解释顺序"
    }
  },
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.82,
    "completeness": 50,
    "source_types": ["USER_EXPLICIT", "LEARNING_BEHAVIOR"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "PROFILE_AGENT",
    "update_reason": "用户表达及学习行为推断偏好",
    "valid_until": "2026-09-15T00:00:00Z"
  }
}
```

规则：
- 只记录稳定偏好，不要因为一次请求格式就写入长期偏好
- 不要写入 selection_rate、completion_rate、post_learning_accuracy_rate 等行为统计字段（由后端统计）

### Metadata 填写要求

更新此维度时必须同时更新 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：用户明确表达的偏好给 0.85+，多次行为一致的给 0.8+，单次行为不写入
- `completeness`：粗估偏好大类（资源/解释/练习/反馈/交互/科目覆盖）已填充比例
- `source_types`：如 `["USER_EXPLICIT"]` 或 `["USER_EXPLICIT", "LEARNING_BEHAVIOR"]`
- `update_reason`：说明更新了哪类偏好及原因
- `valid_until`：当前时间 + 90天
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
