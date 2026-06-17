维度：learning_background

允许更新的字段结构：

```json
{
  "education": {
    "education_level": "学历阶段",
    "grade": "年级",
    "major_code": "专业编码",
    "major_name": "专业名称",
    "enrollment_year": "入学年份"
  },
  "prior_experience": {
    "completed_subjects": {
      "{subject_id}": {
        "subject_id": "科目 ID",
        "subject_name": "科目名称",
        "completion_status": "COMPLETED | IN_PROGRESS | DROPPED",
        "self_reported_level": "用户自述水平",
        "verified_level": "验证水平"
      }
    },
    "programming_languages": {
      "{lang}": {
        "self_reported_level": "BEGINNER | INTERMEDIATE | ADVANCED",
        "verified_level": "验证水平",
        "experience_months": "经验月数"
      }
    },
    "prerequisite_foundation": {
      "{foundation_id}": {
        "status": "SATISFIED | PARTIALLY_SATISFIED | NOT_SATISFIED",
        "score": "分数 0-100"
      }
    }
  },
  "learning_constraints": {
    "weekly_available_minutes": "每周可用学习时间",
    "preferred_study_periods": ["MORNING", "AFTERNOON", "EVENING"],
    "max_session_minutes": "单次最长学习时长",
    "device_types": ["PC", "MOBILE"],
    "network_condition": "STABLE | UNSTABLE",
    "accessibility_needs": []
  },
  "metadata": {
    "schema_version": "1.0",
    "confidence": 0.85,
    "completeness": 40,
    "source_types": ["USER_EXPLICIT"],
    "evidence_ids": [],
    "last_updated_at": "2026-06-17T00:00:00Z",
    "updated_by": "PROFILE_AGENT",
    "update_reason": "用户自述学习背景信息",
    "valid_until": null
  }
}
```

规则：只记录用户明确自述的背景信息，不要推断成绩、掌握分数或客观能力。

### Metadata 填写要求

更新此维度时必须同时更新 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：取本次更新中所有字段的最低置信度
- `completeness`：粗估该维度已填充程度（学历、经验、约束三大部分）
- `source_types`：填写本次更新的信息来源，如 `["USER_EXPLICIT"]`
- `update_reason`：一句话说明更新原因
- `valid_until`：学习背景长期有效，填 `null`
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
