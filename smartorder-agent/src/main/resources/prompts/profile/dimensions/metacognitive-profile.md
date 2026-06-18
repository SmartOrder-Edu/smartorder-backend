维度：metacognitive_profile

允许更新的字段结构：

```json
{
  "overall_level": "BEGINNER | DEVELOPING | PROFICIENT | MASTERED",
  "dimensions": {
    "{dim_code}": {
      "dimension_code": "子维度编码",
      "dimension_name": "子维度名称",
      "score": 0,
      "level": "BEGINNER | DEVELOPING | PROFICIENT | MASTERED",
      "trend": "IMPROVING | STABLE | DECLINING",
      "description": "描述"
    }
  },
  "calibration_statistics": {
    "calibration_status": "OVERCONFIDENT | UNDERCONFIDENT | WELL_CALIBRATED"
  },
  "recommended_support": {
    "planning_support_level": "LOW | MEDIUM | HIGH",
    "reflection_prompt_frequency": "反思提示频率",
    "recommended_interventions": ["建议干预措施"]
  },
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.80,
    "completeness": 40,
    "source_types": ["DIALOGUE_INFERENCE", "LEARNING_BEHAVIOR"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "PROFILE_AGENT",
    "update_reason": "多会话积累后推断元认知水平",
    "valid_until": "2026-07-17T00:00:00Z"
  }
}
```

8 个子维度编码：
- `PLANNING` 学习规划
- `PROCESS_MONITORING` 过程监控
- `SELF_EVALUATION` 自我评估
- `REFLECTION` 学习反思
- `HELP_SEEKING` 主动求助
- `CONFIDENCE_CALIBRATION` 信心校准
- `STRATEGY_ADJUSTMENT` 策略调整
- `TIME_MANAGEMENT` 时间管理

规则：
- 元认知不能根据一两次对话就下结论，建议至少积累多个学习会话后再更新
- 不要写入 overall_score（由后端计算）

### Metadata 填写要求

更新此维度时必须同时更新 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：元认知维度需要更多证据，建议置信度不低于 0.85
- `completeness`：粗估 8 个子维度已评估的比例
- `source_types`：如 `["DIALOGUE_INFERENCE", "LEARNING_BEHAVIOR"]`
- `update_reason`：说明更新了哪些子维度及原因
- `valid_until`：当前时间 + 30天
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
