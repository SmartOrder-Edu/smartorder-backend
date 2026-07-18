---
name: single-choice
description: 生成单选题，固定选项数量，包含结构化选项解析，正确选项唯一
when_to_use: 当请求题型编码为 single_choice 或需要生成单选题时加载
keywords:
  - 单选题
  - single_choice
  - 选择题
  - 单选
priority: 90
auto_invoke: false
question_type: single_choice
objective: true
default_score: 5
default_option_count: 4
---

生成单选题，type 固定为 "单选题"。

# 题目约束

1. 每题必须有 {{optionCount}} 个选项。
2. options 为字符串数组，按顺序对应 A、B、C、D……
3. answer 为单个大写字母字符串。
4. 必须且只能有一个正确选项。
5. 不得使用"以上都对""以上都不对""无法判断"等逃避性选项。
6. 每个错误选项必须对应一种可以明确说明的真实误区。
7. 错误选项要有迷惑性，但在题目给定条件下必须明确不成立。
8. score 固定为 5。

# analysis 专属结构

analysis 除通用字段外，必须包含 option_analysis。

option_analysis 是对象：

- key 必须完整覆盖 A、B、C、D……全部选项；
- 不得缺失或重复任何选项；
- 只有 answer 对应项的 is_correct 可以为 true；
- 其余选项的 is_correct 必须为 false。

每个选项结构固定为：

{
  "is_correct": boolean,
  "reason": string,
  "misconception": string 或 null,
  "correction": string 或 null
}

正确选项：

- reason 说明该选项符合什么知识规则；
- misconception 固定为 null；
- correction 固定为 null。

错误选项：

- reason 说明选项陈述与题目条件之间的具体冲突；
- misconception 指出容易混淆的知识点；
- correction 给出正确理解方式；
- 不得只写"该项错误"或重复正确答案。

# analysis 示例

{
  "schema_version": 2,
  "summary": "本题考查栈的访问规则，正确答案为 B。",
  "reasoning_steps": [
    "识别题目考查的是元素访问顺序",
    "回忆栈只能直接操作栈顶",
    "根据后进先出规则确定答案"
  ],
  "option_analysis": {
    "A": {
      "is_correct": false,
      "reason": "先进先出描述的是普通队列，而栈中最后进入的元素会最先离开。",
      "misconception": "混淆栈与队列",
      "correction": "栈是后进先出，队列是先进先出。"
    },
    "B": {
      "is_correct": true,
      "reason": "栈的插入和删除都在栈顶完成，因此最后入栈的元素最先出栈。",
      "misconception": null,
      "correction": null
    }
  },
  "common_misconceptions": [
    "记反栈和队列的访问顺序"
  ]
}
