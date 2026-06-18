## 公共 Metadata 结构

每个维度更新时，必须同时更新该维度的 `metadata` 字段。结构如下：

```json
{
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.85,
    "completeness": 50,
    "source_types": ["USER_EXPLICIT"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "PROFILE_AGENT",
    "update_reason": "初始化画像模板",
    "valid_until": null
  }
}
```

### 字段规则

| 字段 | 类型 | 说明 | 谁来填 |
|------|------|------|--------|
| `schema_version` | string | 固定 `"1.0"` | 固定值 |
| `confidence` | number 0-1 | 本次更新的整体置信度，取所有 delta 中最低的 confidence | 模型填写 |
| `completeness` | number 0-100 | 该维度已填充字段的完整度百分比（粗估） | 模型填写 |
| `source_types` | string[] | 本次更新的信息来源类型 | 模型填写 |
| `evidence_ids` | string[] | 本次更新关联的证据 ID 列表 | 后端填写，模型不写 |
| `last_updated_at` | ISO 8601 | 本次更新时间 | 后端填写，模型不写 |
| `updated_by` | string | 更新者标识，如 `PROFILE_AGENT`、`ASSESSMENT_AGENT` | 后端填写，模型不写 |
| `update_reason` | string | 一句话说明本次更新原因 | 模型填写 |
| `valid_until` | ISO 8601 \| null | 本次更新结果的有效期，null 表示长期有效 | 模型填写（可选） |

### source_types 可选值

- `USER_EXPLICIT` — 用户明确陈述
- `MODEL_INFERRED` — 模型从对话中推断
- `QUIZ_RESULT` — 测验结果
- `CODE_EVALUATION` — 代码评测结果
- `LEARNING_BEHAVIOR` — 学习行为数据
- `DIALOGUE_INFERENCE` — 对话推断
- `TEACHER_EVALUATION` — 教师评价
- `SYSTEM_DERIVED` — 系统计算

### valid_until 推导规则

| 场景 | 有效期 |
|------|--------|
| 学习背景（学历、专业等） | null（长期有效） |
| 学习目标 | 目标截止时间 |
| 学习偏好 | 当前时间 + 90天 |
| 元认知 | 当前时间 + 30天 |
| 综合能力 | 当前时间 + 30天 |
| 易错模式 | 当前时间 + 30天（已解决则 null） |
| 科目画像 - current_learning_state | 当前时间 + 24小时 |
| 科目画像 - knowledge_mastery | 当前时间 + 7天 |

### 注意事项

- 模型只需填写 `confidence`、`completeness`、`source_types`、`update_reason`、`valid_until`
- `evidence_ids`、`last_updated_at`、`updated_by` 由后端在合并时自动填充，模型不要写入
- `completeness` 是粗估值：如果维度的主要字段都已填充，给 80-100；如果只填了少数字段，给 30-60
