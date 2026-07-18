---
name: multimodal
description: 生成多模态题，包含图表/流程/代码片段等素材，考察综合分析能力
when_to_use: 当请求题型编码为 multimodal 或需要生成多模态题时加载
keywords:
  - 多模态题
  - multimodal
  - 图表
  - 综合分析
priority: 90
auto_invoke: false
question_type: multimodal
objective: false
default_score: 15
---

生成多模态题，type 固定为 "多模态题"。

约束：
1. options 必须是空数组 []。
2. question 必须包含可用文本表达的素材信息，如图表数据、流程描述、代码片段、实验现象、日志片段或界面截图描述。
3. 素材描述必须完整到足以独立作答，不依赖真实图片文件或外部链接。
4. 题目要求学生结合素材观察、分析、判断或解释。
5. answer 格式：精炼的参考答案文本字符串。
6. 多行素材、代码和表格必须在 JSON 字符串中使用 \n 转义。
7. analysis.correct_reason 简述评分要点。
8. analysis.wrong_reason 为空对象 {}。
9. score 默认 15。

# 最终输出
只输出合法 JSON，不输出 Markdown、代码块、解释性文字或思考过程。最外层结构固定为 {"questions": [...]}。

# 短示例
{
  "questions": [{
    "question": "阅读栈操作序列图表描述：容量为 3，操作依次为 push A、push B、pop、push C。请写出每步后栈内元素和最终栈顶。",
    "type": "多模态题",
    "options": [],
    "answer": "push A 后 [A]；push B 后 [A,B]；pop 弹出 B 后 [A]；push C 后 [A,C]，最终栈顶为 C。",
    "score": 15,
    "difficulty": 5,
    "knowledge_points": ["数据结构", "栈"],
    "ability_tags": ["综合分析", "数据结构理解"],
    "analysis": {
      "correct_reason": "栈是后进先出，pop 删除最近入栈的 B，最终栈顶为 C。",
      "wrong_reason": {}
    }
  }]
}
