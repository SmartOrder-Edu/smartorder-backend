---
name: multiple-choice
description: 生成多选题，固定4个选项，至少2个正确答案，包含逐项解析
when_to_use: 当请求题型编码为 multiple_choice 或需要生成多选题时加载
keywords:
  - 多选题
  - multiple_choice
  - 多选
priority: 90
auto_invoke: false
question_type: multiple_choice
objective: true
default_score: 8
default_option_count: 4
---

生成多选题，type 固定为 "多选题"。

# 题目约束

1. 每题固定生成 4 个选项，对应 A、B、C、D。
2. 题干必须明确标注"多选，至少有两个正确答案"。
3. answer 必须为大写字母字符串数组，例如 ["A","C"]。
4. 正确答案必须有 2-3 个，不得四项全部正确。
5. 每个选项必须能够独立判断，不得依赖"以上选项"。
6. 错误选项必须在题目条件下明确不成立。
7. score 固定为 8。

# analysis 专属结构

analysis 必须包含 option_analysis，并完整覆盖 A、B、C、D。

1. answer 中的所有选项必须设置 is_correct=true。
2. 不在 answer 中的所有选项必须设置 is_correct=false。
3. is_correct=true 的选项集合必须与 answer 完全一致。
4. 不得把正确选项同时当成错误选项分析。
5. 出题阶段只说明每个选项本身是否成立。
6. "用户误选了什么、漏选了什么"由提交答案后计算，不得写入静态解析。

错误选项必须包含：

- reason：为什么该说法不成立；
- misconception：容易混淆的概念；
- correction：正确说法或判断方式。

正确选项的 misconception 和 correction 固定为 null。

# 特别禁止

禁止出现当前旧示例中的情况：

- 某选项在 correct_reason 中被判定正确；
- 同时又在 wrong_reason 中被当成"漏选项"。

正确选项为什么不能漏选，直接由它自己的正确 reason 表达。

# analysis 示例

{
  "schema_version": 2,
  "summary": "本题考查普通队列的操作位置以及循环队列的空间复用机制。",
  "reasoning_steps": [
    "判断普通队列的入队和出队方向",
    "判断各选项是否符合先进先出原则",
    "检查循环队列是否能够复用已释放空间"
  ],
  "option_analysis": {
    "A": {
      "is_correct": true,
      "reason": "普通队列按照元素进入的先后顺序出队，因此遵循先进先出。",
      "misconception": null,
      "correction": null
    },
    "B": {
      "is_correct": true,
      "reason": "普通队列通常在队尾插入新元素，以维持进入顺序。",
      "misconception": null,
      "correction": null
    },
    "C": {
      "is_correct": false,
      "reason": "普通队列通常从队头删除元素，而不是从队尾删除。",
      "misconception": "混淆入队端和出队端",
      "correction": "队尾负责入队，队头负责出队。"
    },
    "D": {
      "is_correct": true,
      "reason": "循环队列通过下标回绕复用数组中已经释放的位置。",
      "misconception": null,
      "correction": null
    }
  },
  "common_misconceptions": [
    "认为队列的插入和删除都发生在队尾",
    "不了解循环队列的下标回绕机制"
  ]
}
