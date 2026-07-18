---
name: code
description: 生成编程题，包含测试用例（样例+隐藏），支持多编程语言
when_to_use: 当请求题型编码为 code 或需要生成编程题时加载
keywords:
  - 编程题
  - code
  - 编程
  - 代码
priority: 90
auto_invoke: false
question_type: code
objective: false
default_score: 15
---

生成编程题，type 固定为 "编程题"，编程语言使用 {{programmingLanguage}}。

约束：
1. options 必须是空数组 []。
2. question 包含问题背景、输入格式、输出格式、约束条件和至少 2 组样例，不泄露核心代码。
3. answer 格式：完整的参考答案代码字符串（{{programmingLanguage}} 语言），必须是完整可运行程序或完整函数，不写伪代码。
4. 代码和多行内容必须在 JSON 字符串中使用 \n 转义。
5. analysis.correct_reason 简述解题思路和关键点。
6. analysis.wrong_reason 为空对象 {}。
7. score 默认 15。
8. 必须生成 test_cases 数组，包含至少 2 个样例用例（is_sample: true）和至少 2 个隐藏用例（is_sample: false）。
9. 每个 test_case 包含字段：input（标准输入字符串）、expected_output（期望输出字符串）、is_sample（布尔值，true=样例用例，false=隐藏用例）、weight（数值，默认 1.0）、time_limit_ms（整数，默认 1000）、memory_limit_mb（整数，默认 128）。
10. 测试用例的 input 和 expected_output 必须严格对应题目的输入输出格式，隐藏用例的数据量应略大于样例用例，用于检验算法效率。
11. 隐藏用例也要给出明确的 input 和 expected_output（后端会保护隐藏用例不泄露给学生）。

# 最终输出
只输出合法 JSON，不输出 Markdown、代码块、解释性文字或思考过程。最外层结构固定为 {"questions": [...]}。

# 短示例
{
  "questions": [{
    "question": "实现一个栈，支持 push、pop、peek。输入第一行为操作数 n，随后 n 行为操作；pop 和 peek 输出结果，空栈输出 EMPTY。约束：1 <= n <= 1000。\n样例1输入：3\npush 1\npeek\npop\n样例1输出：1\n1",
    "type": "编程题",
    "options": [],
    "answer": "#include <stdio.h>\n#include <string.h>\n#define MAX 1000\nchar stack[MAX][100];\nint top = -1;\nvoid push(char *s) { strcpy(stack[++top], s); }\nchar *pop() { return top >= 0 ? stack[top--] : NULL; }\nchar *peek() { return top >= 0 ? stack[top] : NULL; }\nint main() {\n    int n;\n    scanf(\"%d\\n\", &n);\n    while (n--) {\n        char line[200];\n        fgets(line, sizeof(line), stdin);\n        line[strcspn(line, \"\\n\")] = 0;\n        char cmd[10], val[100];\n        sscanf(line, \"%s %s\", cmd, val);\n        if (strcmp(cmd, \"push\") == 0) push(val);\n        else if (strcmp(cmd, \"pop\") == 0) { char *r = pop(); printf(\"%s\\n\", r ? r : \"EMPTY\"); }\n        else { char *r = peek(); printf(\"%s\\n\", r ? r : \"EMPTY\"); }\n    }\n    return 0;\n}",
    "score": 15,
    "difficulty": 4,
    "knowledge_points": ["数据结构", "栈", "C语言"],
    "ability_tags": ["编程实现", "数据结构应用"],
    "analysis": {
      "correct_reason": "用数组模拟栈，push入栈，pop/peek前判断空栈输出EMPTY。",
      "wrong_reason": {}
    },
    "test_cases": [
      {
        "input": "3\npush 1\npeek\npop",
        "expected_output": "1\n1",
        "is_sample": true,
        "weight": 1.0,
        "time_limit_ms": 1000,
        "memory_limit_mb": 128
      },
      {
        "input": "5\npush hello\npush world\npeek\npop\npeek",
        "expected_output": "world\nworld\nhello",
        "is_sample": true,
        "weight": 1.0,
        "time_limit_ms": 1000,
        "memory_limit_mb": 128
      },
      {
        "input": "2\npop\npush test",
        "expected_output": "EMPTY",
        "is_sample": false,
        "weight": 1.0,
        "time_limit_ms": 1000,
        "memory_limit_mb": 128
      },
      {
        "input": "6\npush a\npush b\npop\npush c\npeek\npop\npop",
        "expected_output": "b\nc\nc\na",
        "is_sample": false,
        "weight": 1.0,
        "time_limit_ms": 1000,
        "memory_limit_mb": 128
      }
    ]
  }]
}
