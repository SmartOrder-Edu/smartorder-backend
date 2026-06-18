维度：common_misconceptions

允许更新的字段结构：

```json
{
  "patterns": {
    "{misconception_id}": {
      "misconception_id": "易错模式 ID",
      "title": "标题",
      "category": "CONCEPT_CONFUSION | BOUNDARY_CONDITION_OMISSION | PROCEDURE_ORDER_ERROR | ALGORITHM_SELECTION_ERROR | COMPLEXITY_MISJUDGMENT | CODE_IMPLEMENTATION_ERROR | MEMORY_MODEL_ERROR | CARELESS_READING | KNOWLEDGE_TRANSFER_FAILURE | OVER_RELIANCE_ON_HINT",
      "scope": "CROSS_SUBJECT | SUBJECT_SPECIFIC",
      "description": "详细描述",
      "typical_symptoms": ["典型表现列表"],
      "affected_subject_ids": ["涉及科目"],
      "affected_knowledge_point_ids": ["涉及知识点"],
      "root_cause_hypotheses": [
        { "cause_code": "原因编码", "description": "原因描述", "probability": 0.0 }
      ],
      "severity": "LOW | MEDIUM | HIGH",
      "frequency": "RARE | OCCASIONAL | FREQUENT",
      "persistence": "TRANSIENT | PERSISTENT",
      "status": "ACTIVE | RESOLVED | INACTIVE",
      "confidence": 0.0,
      "intervention": {
        "strategy_codes": ["干预策略编码"],
        "recommended_actions": ["建议干预措施"]
      }
    }
  },
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.78,
    "completeness": 45,
    "source_types": ["CODE_EVALUATION", "DIALOGUE_INFERENCE"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "ASSESSMENT_AGENT",
    "update_reason": "根据练习和对话识别易错模式",
    "valid_until": "2026-07-17T00:00:00Z"
  }
}
```

规则：
- 只在用户明确说不会、不理解、反复做错、基础薄弱时更新
- 至少重复出现两次或有明确高可信证据后才形成稳定易错画像
- 不要写入 occurrence_statistics（由后端统计）

### Metadata 填写要求

更新此维度时必须同时更新 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：取本次更新中所有 pattern 的最低置信度
- `completeness`：粗估已识别的易错模式覆盖程度
- `source_types`：如 `["USER_EXPLICIT"]`、`["CODE_EVALUATION", "DIALOGUE_INFERENCE"]`
- `update_reason`：说明新增/合并/解决了哪个易错模式
- `valid_until`：当前时间 + 30天（已解决的 pattern 填 `null`）
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
