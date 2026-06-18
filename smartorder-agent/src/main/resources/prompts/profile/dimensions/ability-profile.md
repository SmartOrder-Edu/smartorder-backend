维度：ability_profile

允许更新的字段结构：

```json
{
  "overall_level": "BEGINNER | DEVELOPING | PROFICIENT | MASTERED",
  "abilities": {
    "{ability_code}": {
      "ability_code": "能力编码",
      "ability_name": "能力名称",
      "level": "BEGINNER | DEVELOPING | PROFICIENT | MASTERED",
      "trend": "IMPROVING | STABLE | DECLINING",
      "strength_summary": "优势描述",
      "improvement_focus": "改进方向",
      "confidence": 0.0
    }
  },
  "strength_ability_codes": ["优势能力编码列表"],
  "development_priority_codes": ["优先发展能力编码列表"],
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.80,
    "completeness": 55,
    "source_types": ["USER_EXPLICIT", "CODE_EVALUATION"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "ASSESSMENT_AGENT",
    "update_reason": "根据练习和代码评测评估综合能力",
    "valid_until": "2026-07-17T00:00:00Z"
  }
}
```

9 项能力维度编码：
- `CONCEPT_UNDERSTANDING` 概念理解
- `ABSTRACTION_MODELING` 抽象建模
- `PROBLEM_DECOMPOSITION` 问题拆解
- `ALGORITHM_DESIGN` 算法设计
- `COMPLEXITY_ANALYSIS` 复杂度分析
- `CODING_IMPLEMENTATION` 代码实现
- `DEBUGGING` 调试分析
- `KNOWLEDGE_TRANSFER` 知识迁移
- `TECHNICAL_EXPRESSION` 技术表达

规则：只记录用户明确自评的能力强弱，不要让模型直接生成 score、overall_score 或客观测评分数。

### Metadata 填写要求

更新此维度时必须同时更新 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：取本次更新中所有能力项的最低置信度
- `completeness`：粗估 9 项能力中已评估的比例（如评估了 6 项则约 67）
- `source_types`：如 `["USER_EXPLICIT"]` 或 `["USER_EXPLICIT", "CODE_EVALUATION"]`
- `update_reason`：说明更新了哪些能力项及原因
- `valid_until`：当前时间 + 30天
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
