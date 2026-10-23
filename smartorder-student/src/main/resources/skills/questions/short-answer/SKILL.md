---
name: short-answer
description: 生成简答题，包含评分量规和常见错误分析，支持多级评分
when_to_use: 当请求题型编码为 short_answer 或需要生成简答题时加载
keywords:
  - 简答题
  - short_answer
  - 简答
  - 问答
priority: 90
auto_invoke: false
question_type: short_answer
objective: false
default_score: 10
---

生成简答题，type 固定为 "简答题"。

# 题目约束

1. options 固定为 []。
2. 题干聚焦一个核心问题，可以要求解释原因、比较差异、说明步骤或分析机制。
3. 题目的作答要求必须明确，不得使用"谈谈你的理解"等无法稳定判分的表述。
4. answer 必须是完整、准确且能够覆盖全部评分点的参考答案字符串。
5. score 只能设置为 8、10 或 12：
   - 基础解释题：8 分；
   - 包含比较或多个要点：10 分；
   - 包含机制分析或综合应用：12 分。
6. 多行内容必须使用 \n 转义。

# analysis 专属结构

analysis 除通用字段外，必须包含 rubric 和 common_errors。

rubric 是评分点数组，每项结构为：

{
  "id": string,
  "criterion": string,
  "points": int,
  "required": boolean,
  "full_credit": string,
  "partial_credit": string,
  "no_credit": string
}

common_errors 结构为：

{
  "code": string,
  "label": string,
  "reason": string,
  "correction": string
}

# rubric 规则

1. rubric 必须包含 2-5 个独立评分点。
2. 所有 points 之和必须严格等于题目 score。
3. id 按 R1、R2、R3……连续生成。
4. criterion 必须是可以从学生答案中观察和判断的具体要求。
5. 一个评分点只能考查一个原子要求。
6. full_credit 必须说明获得该项满分需要包含什么内容。
7. partial_credit 必须说明哪些不完整回答可以获得部分分。
8. no_credit 必须说明哪些错误、缺失或概念混淆不得分。
9. required=true 的评分点必须在参考答案中有明确依据。
10. 除非题干明确要求，不得将字数、措辞或格式作为知识性扣分项。
11. 不得使用"回答合理""表述较好""基本正确"等无法执行的评分标准。

# common_errors 规则

1. 生成 1-3 个高概率错误或遗漏。
2. 优先覆盖概念写反、条件遗漏、只回答部分要求、例子与原理不匹配。
3. reason 说明为什么该回答不完整或不准确。
4. correction 说明需要补充或改正的内容。

# analysis 示例

{
  "schema_version": 2,
  "summary": "完整回答需要比较栈和队列的访问顺序，并分别给出符合其结构特点的应用。",
  "reasoning_steps": [
    "先分别说明栈和队列的访问规则",
    "再比较两种结构处理元素顺序的差异",
    "最后为两种结构各给出一个匹配的应用"
  ],
  "rubric": [
    {
      "id": "R1",
      "criterion": "准确说明栈遵循后进先出",
      "points": 2,
      "required": true,
      "full_credit": "明确写出后进先出，并能说明最后入栈元素最先出栈。",
      "partial_credit": "提到后进先出，但没有解释其含义。",
      "no_credit": "写成先进先出或完全未提及栈的访问顺序。"
    },
    {
      "id": "R2",
      "criterion": "准确说明队列遵循先进先出",
      "points": 2,
      "required": true,
      "full_credit": "明确写出先进先出，并能说明最早入队元素最先出队。",
      "partial_credit": "提到先进先出，但没有解释其含义。",
      "no_credit": "写成后进先出或完全未提及队列的访问顺序。"
    },
    {
      "id": "R3",
      "criterion": "给出符合栈特点的典型应用",
      "points": 2,
      "required": true,
      "full_credit": "给出函数调用栈、撤销或表达式求值等合理示例，并能对应后进先出。",
      "partial_credit": "示例正确，但没有说明与后进先出的关系。",
      "no_credit": "示例属于队列应用或未提供示例。"
    },
    {
      "id": "R4",
      "criterion": "给出符合队列特点的典型应用",
      "points": 2,
      "required": true,
      "full_credit": "给出任务排队或广度优先搜索等合理示例，并能对应先进先出。",
      "partial_credit": "示例正确，但没有说明与先进先出的关系。",
      "no_credit": "示例属于栈应用或未提供示例。"
    }
  ],
  "common_errors": [
    {
      "code": "REVERSE_ORDER",
      "label": "访问顺序写反",
      "reason": "把栈和队列的核心访问规则互换，会导致后续应用判断全部错误。",
      "correction": "记住栈是 LIFO，队列是 FIFO。"
    },
    {
      "code": "EXAMPLES_ONLY",
      "label": "只有示例没有原理",
      "reason": "题目要求说明主要区别，只列举应用不能证明理解了结构特点。",
      "correction": "应先说明访问规则，再用应用示例验证。"
    }
  ],
  "common_misconceptions": [
    "把栈和队列的访问顺序写反",
    "只列举应用而没有说明结构区别"
  ]
}
