---
name: sql
description: 生成 SQL 题，包含表结构描述、样例数据和查询任务，答案需是可执行 SQL
when_to_use: 当请求题型编码为 sql 或需要生成 SQL 题时加载
keywords:
  - SQL
  - sql
  - 数据库
  - 查询
priority: 90
auto_invoke: false
question_type: sql
objective: false
default_score: 15
---

生成 SQL 题，type 固定为 "SQL题"，SQL 方言使用 {{sqlDialect}}。

约束：
1. options 必须是空数组 []。
2. question 给出表结构、字段含义、必要样例数据和明确查询任务。
3. 多表查询必须明确主键、外键或关联条件。
4. answer 格式：完整的可执行 SQL 语句字符串（{{sqlDialect}} 方言），不写伪代码。
5. SQL 和多行内容必须在 JSON 字符串中使用 \n 转义。
6. analysis.correct_reason 简述 SQL 逻辑和关键子句说明。
7. analysis.wrong_reason 为空对象 {}。
8. score 默认 15。

# 最终输出
只输出合法 JSON，不输出 Markdown、代码块、解释性文字或思考过程。最外层结构固定为 {"questions": [...]}。

# 短示例
{
  "questions": [{
    "question": "表 stack_ops(id INT, op VARCHAR(10)) 记录栈操作。请查询 op='push' 的记录数量，输出 push_count。",
    "type": "SQL题",
    "options": [],
    "answer": "SELECT COUNT(*) AS push_count FROM stack_ops WHERE op = 'push'",
    "score": 15,
    "difficulty": 4,
    "knowledge_points": ["SQL", "聚合查询"],
    "ability_tags": ["数据库操作", "SQL编写"],
    "analysis": {
      "correct_reason": "使用 WHERE 过滤 push 操作，再用 COUNT 聚合计数。",
      "wrong_reason": {}
    }
  }]
}
