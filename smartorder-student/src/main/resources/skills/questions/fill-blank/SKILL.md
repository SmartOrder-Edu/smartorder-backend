---
name: fill-blank
description: 生成填空题，每题1-3个空，包含空位解析和常见错误分析
when_to_use: 当请求题型编码为 fill_blank 或需要生成填空题时加载
keywords:
  - 填空题
  - fill_blank
  - 填空
priority: 90
auto_invoke: false
question_type: fill_blank
objective: true
default_score: 5
---

生成填空题，type 固定为 "填空题"。

# 题目约束

1. 每题包含 1-3 个空，每个空统一使用 ____。
2. options 固定为 []。
3. answer 必须是字符串数组，并按空位出现顺序排列。
4. answer 数量必须与题干中 ____ 的数量完全一致。
5. 每个空只能考查一个明确知识点，不能存在多个含义不同但都成立的答案。
6. 不得依赖题干之外的上下文。
7. score 默认设置为 5。

# analysis 专属结构

analysis 除通用字段外，必须包含 blank_analysis。

blank_analysis 是数组，每个空包含：

{
  "index": int,
  "accepted_answers": string[],
  "reason": string,
  "common_errors": [
    {
      "example": string,
      "reason": string,
      "correction": string
    }
  ]
}

# blank_analysis 规则

1. blank_analysis 数量必须与 answer 数量一致。
2. index 必须从 1 开始连续递增，不得重复或缺失。
3. accepted_answers 第一项必须等于 answer 对应位置的标准答案。
4. accepted_answers 最多 3 个，只允许含义完全等价的写法、缩写或大小写变体。
5. 不得把部分正确、范围更宽或概念相近的词加入 accepted_answers。
6. reason 必须结合当前空位所在句子，说明为什么应填写该答案。
7. 每空最多生成 1 个高概率 common_errors，整题至少生成 1 个。
8. 不得为了凑数量编造不真实的错误答案。
9. common_errors.example 不能与 accepted_answers 中任何答案等价。
10. common_errors.reason 说明错误答案混淆了什么知识。
11. correction 给出正确的辨析方式。

# analysis 示例

{
  "schema_version": 2,
  "summary": "本题考查队列和栈在元素访问顺序上的本质区别。",
  "reasoning_steps": [
    "先回忆队列的入队和出队位置",
    "再回忆栈顶元素的操作顺序",
    "根据两种结构的访问规则完成填空"
  ],
  "blank_analysis": [
    {
      "index": 1,
      "accepted_answers": ["先进先出", "FIFO"],
      "reason": "队列从队尾加入元素、从队头移除元素，因此最早进入的元素最先离开。",
      "common_errors": [
        {
          "example": "后进先出",
          "reason": "后进先出描述的是栈，说明混淆了队列和栈的访问顺序。",
          "correction": "队列是先进先出，栈才是后进先出。"
        }
      ]
    },
    {
      "index": 2,
      "accepted_answers": ["后进先出", "LIFO"],
      "reason": "栈主要操作栈顶，最后压入栈顶的元素会最先被弹出。",
      "common_errors": [
        {
          "example": "先进先出",
          "reason": "先进先出描述的是队列，不符合栈顶元素后入先出的特点。",
          "correction": "判断栈的顺序时，应关注最后压入栈顶的元素。"
        }
      ]
    }
  ],
  "common_misconceptions": [
    "记反栈和队列的访问顺序"
  ]
}
