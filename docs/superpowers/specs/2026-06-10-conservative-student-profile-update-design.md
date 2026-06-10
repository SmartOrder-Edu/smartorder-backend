# 学生画像保守实时更新设计

## 目标

让主 ReAct Agent 在对话中发现用户明确表达的学习证据时，通过
`updateStudentProfile` 增量更新学生画像，同时避免把普通提问误判为薄弱点或偏好。

本次只接入 `REACT` 模式。`PLAN_EXECUTE` 继续只读取画像，`MULTI_AGENT`
继续专注资源生成，不直接写画像。

## 核心原则

1. 用户讨论某个知识点，不等于用户不掌握该知识点。
2. 只有当前用户消息中的明确自述或明确表现，才能作为画像更新证据。
3. 模型负责发现候选证据，服务端负责最终校验，不能只依赖 Prompt。
4. 所有更新都是维度级增量合并，不覆盖其他画像维度。
5. 一轮对话发现多个维度时，合并成一次工具调用和一次数据库更新。

## 接入范围

### 开放 `updateStudentProfile` 的 ReAct 意图

- `KNOWLEDGE_QA`
- `PROBLEM_HELP`
- `DIAGRAM_GENERATION`
- `PROGRESS_REVIEW`
- `TASK_MANAGEMENT`
- `UNKNOWN` 和默认 ReAct 兜底

所有非澄清 `REACT` 请求都允许模型提出画像更新。普通提问和一次性格式指令
仍会由服务端证据门控拒绝，因此“开放工具”不等于“允许直接写入”。

### 暂不开放的意图和模式

- `GOAL_PLANNING` / `PLAN_CONFIRMATION`：规划流程只读取画像。
- `RESOURCE_GENERATION` / `MULTI_AGENT`：资源生成子 Agent 不写画像。
- 澄清模式：`requiredTools` 为空，不执行画像更新。

## 数据结构

`UpdateStudentProfileRequest.DimensionUpdate` 增加：

```json
{
  "dimensionKey": "weakness_profile",
  "delta": {
    "weak_points": [
      {
        "topic": "死锁",
        "weakness_type": "concept_confusion"
      }
    ]
  },
  "evidence": "我总是搞不懂死锁",
  "confidence": 0.9,
  "reason": "用户明确表达对死锁概念长期理解困难"
}
```

字段约束：

- `evidence`：必填，必须能在当前用户消息中找到。
- `confidence`：必填，取值范围 `0.0` 到 `1.0`，最低接受值为 `0.8`。
- `reason`：必填，说明证据和更新维度之间的关系。
- `dimensionKey` 和 `delta` 延续现有白名单与非空校验。

## 运行时上下文

`ToolInvokeContext` 增加 `currentUserMessage`，由 `SelfManagedReActEngine`
在创建工具上下文时传入当前请求的原始消息。

工具不能从模型参数中接受 `userId` 或当前消息，防止模型伪造身份和证据来源。

## 服务端证据门控

`UpdateStudentProfileTool` 在调用画像服务前执行以下校验：

1. 当前用户消息不能为空。
2. `evidence` 不能为空，去除首尾空白后必须是当前消息的连续子串。
3. `confidence` 必须在 `0.8` 到 `1.0` 之间。
4. `reason` 不能为空。
5. 证据必须包含与目标维度匹配的明确自述信号：
   - 基础信息：“我是”“我的专业是”“我目前”等。
   - 薄弱点或掌握程度：“我不会”“我不理解”“我总是”“我一直”“连续做错”等。
   - 学习偏好或认知方式：“我喜欢”“我更喜欢”“对我来说更容易”“我习惯”等。
   - 行为或情绪特征：“我通常”“我经常”“每天”“学习效率”“一到考试就紧张”等。
6. 普通请求句和一次性格式指令不能单独作为证据，例如：
   - “解释一下死锁”
   - “什么是死锁”
   - “讲一下死锁”
   - “这次用流程图解释”
7. 任一维度校验失败时，整次批量更新拒绝，避免部分画像已写入。

明确证据示例：

- “我总是搞不懂死锁”
- “这类死锁题我连续做错了三次”
- “我更喜欢通过流程图理解概念”
- “我晚上学习效率比早上高”

## Prompt 约束

主 Agent System Prompt 补充：

- 普通提问、指定一次输出格式、请求讲解，不构成画像证据。
- 只有用户明确自述薄弱点、稳定偏好、掌握程度或行为特征时才能调用更新工具。
- `evidence` 必须原样摘自当前用户消息，不能总结、改写或引用历史助手回复。
- 证据不足时正常回答，不要为了更新画像而调用工具。

## 数据流

```text
用户消息
  -> IntentRouteService 选择 REACT 并开放候选工具
  -> SelfManagedReActEngine 将工具 Schema 发送给模型
  -> 模型发现明确证据并提出 updateStudentProfile 调用
  -> ToolInvokeContext 提供可信的当前用户原文
  -> UpdateStudentProfileTool 校验证据、置信度和维度
  -> StudentProfileService 执行增量合并
  -> 工具结果回灌 ReAct 循环
  -> Agent 继续生成最终回答
```

## 错误处理

- 参数格式错误：抛出工具参数异常，允许 ReAct 下一轮修正参数。
- 证据或置信度不合格：返回结构化拒绝结果，不写数据库。
- 画像服务异常：返回更新失败结果，并记录用户、维度和 trace 日志。
- 画像更新失败不能阻断主问题回答，模型应继续完成原始学习任务。

## 测试

### 路由测试

- 所有非澄清 `REACT` 意图的工具列表包含 `updateStudentProfile`。
- `GOAL_PLANNING`、`PLAN_CONFIRMATION` 和 `RESOURCE_GENERATION`
  不包含该工具。
- 槽位不足进入澄清模式后，工具列表为空。

### 工具测试

- 明确薄弱点证据且置信度足够时执行单维度更新。
- 明确多个证据时执行一次批量更新。
- evidence 不在当前消息中时拒绝。
- confidence 低于 `0.8` 时拒绝。
- 普通“解释一下死锁”不更新薄弱点。
- 一次性“这次用流程图解释”不更新学习偏好。
- 非法维度、空 delta、空 reason 时拒绝。
- 校验失败时画像服务零调用。

## 非目标

- 不新增独立画像分析 Agent。
- 不从历史多轮消息自动抽取画像证据。
- 不在 Multi-Agent 子任务中更新画像。
- 不修改现有画像表结构或深度合并算法。
- 不自动删除或降低已有画像结论。
