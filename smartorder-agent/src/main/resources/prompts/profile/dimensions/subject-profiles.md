维度：subject_profiles

按科目 ID 组织，每个科目下包含多个子维度。

允许更新的字段结构：

```json
{
  "{subject_id}": {
    "subject_id": "科目 ID",
    "subject_name_cache": "科目名称",
    "subject_status": "ACTIVE | PAUSED | COMPLETED | DROPPED",
    "subject_goals": { "goal_ids": ["关联目标 ID"] },

    "knowledge_mastery": {
      "overall_mastery_level": "UNKNOWN | NOT_STARTED | EXPOSED | BEGINNER | DEVELOPING | PROFICIENT | MASTERED",
      "current_chapter_id": "当前章节 ID",
      "knowledge_points": {
        "{kp_id}": {
          "knowledge_point_id": "知识点 ID",
          "knowledge_point_name_cache": "知识点名称",
          "mastery_level": "UNKNOWN | NOT_STARTED | EXPOSED | BEGINNER | DEVELOPING | PROFICIENT | MASTERED",
          "cognitive_mastery": {
            "remember": 0, "understand": 0, "apply": 0, "analyze": 0, "implement": 0
          },
          "independence_level": "UNABLE | WITH_FULL_GUIDANCE | WITH_HINT | MOSTLY_INDEPENDENT | FULLY_INDEPENDENT",
          "prerequisite_readiness": {
            "status": "READY | PARTIALLY_READY | NOT_READY",
            "unsatisfied_knowledge_point_ids": []
          },
          "retention": {
            "forgetting_risk": "LOW | MEDIUM | HIGH",
            "next_review_at": "下次复习时间"
          },
          "misconceptions": {
            "{misconception_id}": {
              "misconception_id": "易错模式 ID",
              "title": "标题",
              "status": "ACTIVE | RESOLVED",
              "severity": "LOW | MEDIUM | HIGH"
            }
          }
        }
      }
    },

    "subject_misconceptions": {
      "patterns": { "同 common_misconceptions 结构" }
    },

    "current_learning_state": {
      "learning_status": "NOT_STARTED | LEARNING | PRACTICING | REVIEWING | STUCK | NEED_HELP | PAUSED | COMPLETED",
      "current_knowledge_point_id": "当前知识点",
      "current_task": {
        "task_id": "任务 ID",
        "task_type": "任务类型",
        "title": "任务标题",
        "difficulty": "EASY | MEDIUM | HARD",
        "attempt_count": "尝试次数",
        "hint_count": "提示次数"
      },
      "blocking_issues": {
        "{issue_id}": {
          "issue_id": "问题 ID",
          "issue_type": "问题类型",
          "description": "描述",
          "severity": "LOW | MEDIUM | HIGH",
          "status": "ACTIVE | RESOLVED"
        }
      },
      "session_signals": {
        "engagement_level": "LOW | MEDIUM | HIGH",
        "cognitive_load": "LOW | MEDIUM | HIGH",
        "confusion_level": "LOW | MEDIUM | HIGH",
        "frustration_signal": { "value": "LOW | MEDIUM | HIGH" }
      },
      "readiness": {
        "continue_current_task": true,
        "need_remediation": false,
        "recommended_difficulty": "EASY | MEDIUM | HARD",
        "recommended_support_level": "INDEPENDENT | GUIDED | FULL_GUIDANCE"
      },
      "next_actions": [
        { "order": 1, "action_type": "动作类型", "title": "标题", "estimated_minutes": 0 }
      ],
      "valid_until": "状态有效期 ISO 8601（必填）"
    },

    "learning_preference_override": {
      "preferred_resource_types": ["偏好资源类型"]
    },

    "metadata": {
      "schema_version": "1.0",
      "confidence": 0.80,
      "completeness": 50,
      "source_types": ["QUIZ_RESULT", "CODE_EVALUATION"],
      "evidence_ids": [],
      "last_updated_at": "2026-06-17T00:00:00Z",
      "updated_by": "ASSESSMENT_AGENT",
      "update_reason": "根据测验和代码评测更新科目掌握情况",
      "valid_until": "2026-06-24T00:00:00Z"
    }
  }
}
```

规则：
- 只记录特定科目相关的信息
- 不要写入 attempt_count、accuracy_rate、study_minutes 等由后端统计的字段
- current_learning_state 必须设置 valid_until，不可永久保留
- 使用科目 ID 作为 Map 的键，不要使用数组

### Metadata 填写要求

更新此维度时必须同时更新对应科目的 `metadata`，具体规则见 [metadata-schema.md](metadata-schema.md)。

- `confidence`：取本次更新中所有子维度的最低置信度
- `completeness`：粗估该科目下各子维度（knowledge_mastery、misconceptions、current_state、preference_override）已填充比例
- `source_types`：如 `["USER_EXPLICIT", "CODE_EVALUATION"]`
- `update_reason`：说明更新了哪个科目的哪些子维度
- `valid_until`：
  - current_learning_state 相关更新：当前时间 + 24小时
  - knowledge_mastery 相关更新：当前时间 + 7天
  - 其他：当前时间 + 30天
- 不要填写 `evidence_ids`、`last_updated_at`、`updated_by`（由后端自动填充）
