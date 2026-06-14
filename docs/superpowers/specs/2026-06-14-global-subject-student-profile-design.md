# 学生全局与单科画像后端迁移设计

## 目标

根据 `smartorder-student/src/main/resources/sql/student_profile.sql` 的最新结构，
将后端从旧的单表 `student_profile` 模型迁移到：

- `student_global_profile`：每个用户一条全局画像。
- `student_subject_profile`：每个用户、每个科目一条单科画像。

本次直接切换后端数据模型和接口，不保留旧实体、旧字段或旧
`/student-profile` 返回结构的兼容层。前端适配不在本次范围内。

## 核心原则

1. 全局画像和单科画像是两个独立聚合，不再用 `subject_id IS NULL` 区分。
2. Java 实体、Mapper 和数据库表一一对应，禁止继续使用统一实体反射路由。
3. 画像维度名称严格使用新 SQL 字段名。
4. 全局画像保存跨科目稳定信息和聚合结果；单科画像保存具体科目的事实数据。
5. Agent 可以提出画像候选更新，但 `userId` 和 `subjectId` 必须来自后端上下文。
6. 现有异步画像分析、证据校验和单线程写入策略继续保留。
7. 不自动猜测科目；没有可信 `subjectId` 时，只允许写全局画像。

## 数据模型

### 全局画像

新增 `StudentGlobalProfile`，映射 `student_global_profile`：

- `id`
- `userId`
- `profileVersion`
- `learningBackground`
- `learningGoals`
- `subjectMasteryOverview`
- `abilityProfile`
- `misconceptionProfile`
- `learningPreference`
- `metacognitiveProfile`
- `learningBehavior`
- `currentLearningState`
- `dimensionMetadata`
- `profileSummary`
- `profileConfidenceScore`
- `profileQualityScore`
- `lastUpdateReason`
- `lastAggregatedAt`
- `createdAt`
- `updatedAt`

所有 JSON 字段使用 `JacksonTypeHandler` 映射为 `Map<String, Object>`。

### 单科画像

新增 `StudentSubjectProfile`，映射 `student_subject_profile`：

- `id`
- `userId`
- `subjectId`
- `profileVersion`
- `learningBackground`
- `learningGoals`
- `knowledgeMastery`
- `abilityProfile`
- `misconceptionProfile`
- `learningPreference`
- `metacognitiveProfile`
- `learningBehavior`
- `currentLearningState`
- `dimensionMetadata`
- `profileSummary`
- `profileConfidenceScore`
- `profileCompleteness`
- `lastUpdateReason`
- `createdAt`
- `updatedAt`
- `lastAssessedAt`
- `nextRefreshAt`

`subjectId` 必填。服务端不得创建没有科目 ID 的单科画像。

### 删除旧模型

删除或替换以下旧结构：

- `StudentProfile`
- `StudentProfileMapper`
- `student_profile` 表映射
- `fullProfileSnapshot`
- `basicProfile`
- `knowledgeProfile`
- `preferenceProfile`
- `cognitiveProfile`
- `weaknessProfile`
- `behaviorProfile`
- `emotionProfile`
- `confidenceScore`

新表自身的 JSON 列就是画像事实源，不再维护重复的完整快照。

## 维度映射

旧维度不能直接沿用。后端统一使用下列新名称：

| 旧维度 | 新全局维度 | 新单科维度 |
| --- | --- | --- |
| `basic_profile` | `learning_background` | `learning_background` |
| 基础画像中的学习目标 | `learning_goals` | `learning_goals` |
| `knowledge_profile` | `subject_mastery_overview`，仅系统聚合 | `knowledge_mastery` |
| `ability_profile` | `ability_profile` | `ability_profile` |
| `weakness_profile` | `misconception_profile` | `misconception_profile` |
| `preference_profile` | `learning_preference` | `learning_preference` |
| `cognitive_profile` | `metacognitive_profile` | `metacognitive_profile` |
| `behavior_profile` | `learning_behavior` | `learning_behavior` |
| `emotion_profile` | `current_learning_state` | `current_learning_state` |

`dimension_metadata` 由服务端维护，不作为普通 Agent 自述更新的自由写入维度。
`subject_mastery_overview` 是从单科画像聚合得到的缓存，Agent 不直接写入。

## 持久化边界

新增：

- `StudentGlobalProfileMapper extends BaseMapper<StudentGlobalProfile>`
- `StudentSubjectProfileMapper extends BaseMapper<StudentSubjectProfile>`

服务层保留一个面向业务的 `IStudentProfileService`，但方法明确区分作用域：

```java
StudentGlobalProfile getGlobalProfile(Long userId);

List<StudentSubjectProfile> listSubjectProfiles(Long userId);

StudentSubjectProfile getSubjectProfile(Long userId, Long subjectId);

void saveGlobalProfile(StudentGlobalProfile profile);

void saveSubjectProfile(StudentSubjectProfile profile);

void updateGlobalDimensions(Long userId, List<DimensionUpdateDTO> updates);

void updateSubjectDimensions(Long userId, Long subjectId,
                             List<DimensionUpdateDTO> updates);
```

`DimensionUpdateDTO` 只携带 `dimensionKey`、`delta` 和 `reason`。
作用域与 `subjectId` 由方法签名提供，避免把路由决策隐藏在 DTO 内。

全局和单科更新分别维护显式白名单与 getter/setter 映射。未知维度直接抛出
参数异常，不再通过反射后记录警告并继续执行。

## 更新语义

### JSON 合并

继续使用深度合并：

- Map 与 Map 递归合并。
- 标量、数组和 `null` 使用新值覆盖旧值。
- 不更新未出现在 delta 中的字段。

数组不按元素 ID 自动合并。本次不引入通用数组合并协议，避免不明确的覆盖行为。

### 批量原子性

一轮提取出的多个维度必须在同一个事务中更新：

- 整批先校验。
- 读取一次画像。
- 合并全部有效维度。
- `profileVersion` 只增加一次。
- 写入失败则整批回滚。

### 并发

保留 `profileUpdateExecutor` 单线程执行，避免同一进程中的异步读改写覆盖。
数据库写入增加基于 `profile_version` 的乐观更新：

```sql
UPDATE ...
SET ..., profile_version = profile_version + 1
WHERE id = ? AND profile_version = ?
```

更新影响行数为零时重新读取并重试一次；再次冲突则记录错误并放弃该次异步更新，
不阻断 Agent 主回答。

## 初始化

### 用户注册

注册事件只创建一条空的 `student_global_profile`：

- `profileVersion = 1`
- `profileConfidenceScore = 0`
- `profileQualityScore = 0`
- JSON 维度初始化为空对象
- `lastUpdateReason = 注册创建，待引导页初始化`

注册阶段不创建单科画像，因为尚无可信 `subjectId`。

### 引导页初始化

`ProfileInitDTO` 继续作为引导页输入，但写入新全局维度：

- 身份、年级、方向、当前基础、自我描述写入 `learning_background`。
- 目标、目标详情、截止时间、学习时间写入 `learning_goals`。

初始化完成后重新计算版本号、置信度、质量分和更新时间。

### 单科画像创建

首次调用单科保存或更新接口时按 `(userId, subjectId)` 查询：

- 已存在则增量更新。
- 不存在则创建空单科画像后应用本次更新。

创建前必须验证 `subjectId` 非空且为正数。

## HTTP 接口

`StudentProfileController` 改为直接暴露新数据库模型：

```text
GET  /student-profile/global
PUT  /student-profile/global
POST /student-profile/global/init

GET  /student-profile/subjects
GET  /student-profile/subjects/{subjectId}
PUT  /student-profile/subjects/{subjectId}
```

约束：

- `userId` 始终从登录上下文获取，忽略请求体中的用户 ID。
- 路径中的 `subjectId` 覆盖请求体中的同名字段。
- 更新现有记录时校验记录归属当前用户。
- Controller 不再返回旧 `StudentProfile`。

本次不修改前端调用代码，因此旧前端接口在后端切换后会暂时不可用。

## Agent 读取

`getStudentProfile` 支持两种读取：

- 默认返回全局画像。
- 当后端上下文存在 `subjectId` 时，返回全局画像和该单科画像组成的对象。

返回结构：

```json
{
  "globalProfile": {},
  "subjectProfile": {}
}
```

`subjectProfile` 在没有科目上下文时为 `null`。工具不能接受模型传入的
`userId` 或 `subjectId`。

规划完整性判断改为检查：

- `globalProfile.learningBackground`
- `globalProfile.learningGoals`
- `globalProfile.learningPreference`
- `globalProfile.profileConfidenceScore`

不再读取旧的 `basicProfile`、`knowledgeProfile` 或 `preferenceProfile`。

## Agent 更新

### 上下文传递

`AgentChatRequest` 增加可选 `subjectId`。Controller 接收到该值后，将其放入
`ToolInvokeContext` 和异步画像更新任务。该值属于宿主请求上下文，不由 LLM 生成。

当前端暂未传入 `subjectId` 时：

- Agent 只能更新全局维度。
- 涉及具体科目的知识掌握或具体错误，不直接写单科表。
- 候选提取器可以跳过该候选，不得猜测科目 ID。

### 更新请求

`DimensionUpdate` 增加 `scope`：

```json
{
  "scope": "GLOBAL",
  "dimensionKey": "learning_goals",
  "delta": {},
  "evidence": "我准备考研",
  "confidence": 0.95,
  "reason": "用户明确表达长期学习目标"
}
```

`scope` 可选值：

- `GLOBAL`
- `SUBJECT`

`SUBJECT` 更新必须存在上下文 `subjectId`。请求体中不提供 `subjectId` 字段。

### 全局白名单

- `learning_background`
- `learning_goals`
- `ability_profile`
- `misconception_profile`
- `learning_preference`
- `metacognitive_profile`
- `learning_behavior`
- `current_learning_state`

### 单科白名单

- `learning_background`
- `learning_goals`
- `knowledge_mastery`
- `ability_profile`
- `misconception_profile`
- `learning_preference`
- `metacognitive_profile`
- `learning_behavior`
- `current_learning_state`

`subject_mastery_overview` 和 `dimension_metadata` 不允许通过对话工具直接更新。

### 证据门控

继续保留现有规则：

- evidence 必须逐字来自当前用户消息。
- confidence 必须在 `0.8` 到 `1.0`。
- 普通提问和一次性格式要求不是画像证据。
- 任一维度不合法时整批拒绝。
- 用户身份、科目和作用域由服务端校验。

维度信号表同步改为新字段名。学习目标不再写入
`learning_background`，而是写入 `learning_goals`。

## 自然语言转换

保留画像转自然语言能力，但输入改为新实体或明确的新维度 Map。

维度标签使用新名称：

- `learning_background`：学习背景
- `learning_goals`：学习目标
- `subject_mastery_overview`：科目掌握总览
- `knowledge_mastery`：知识掌握
- `ability_profile`：综合能力
- `misconception_profile`：易错模式
- `learning_preference`：学习偏好
- `metacognitive_profile`：元认知
- `learning_behavior`：学习行为
- `current_learning_state`：当前学习状态
- `dimension_metadata`：维度元数据

转换器只负责展示，不承担字段迁移或旧名称兼容。

## 错误处理

- 非法维度：在写数据库前拒绝。
- `SUBJECT` 缺少上下文科目：返回结构化拒绝，不写数据库。
- 非法 `subjectId`：Controller 或 Service 参数校验失败。
- 画像归属不匹配：拒绝更新并记录安全日志。
- 乐观锁冲突：重试一次，仍失败则记录并跳过异步写入。
- Agent 画像更新失败：不得阻断路由、规划或主回答。
- 查询不到画像：读取接口返回 `null`；更新接口按规则创建。

## 测试

### 实体与 Mapper

- 两个实体分别映射正确表名。
- JSON 字段启用 `JacksonTypeHandler`。
- 不再存在旧 `student_profile` 表映射。

### 服务

- 注册只创建全局画像。
- 引导页数据正确拆分到 `learning_background` 和 `learning_goals`。
- 查询全部单科画像按当前用户过滤。
- 首次单科更新创建记录。
- 全局和单科维度白名单互不混用。
- 多维度更新只增加一次版本号。
- 非法维度不产生数据库写入。
- 乐观锁冲突重试一次。

### Controller

- 全局和单科接口从登录上下文取用户 ID。
- 路径 `subjectId` 生效。
- 不能更新其他用户的画像。
- 旧 `/student-profile` 接口不再暴露。

### Agent

- 没有 `subjectId` 时只允许 `GLOBAL` 更新。
- 有 `subjectId` 时允许 `SUBJECT` 更新并传给单科服务。
- 模型无法通过参数覆盖 `userId` 或 `subjectId`。
- 学习目标进入 `learning_goals`。
- 具体知识掌握进入 `knowledge_mastery`。
- 易错证据进入 `misconception_profile`。
- 原有证据、置信度、普通提问拒绝测试继续通过。
- `getStudentProfile` 正确组合全局和单科画像。
- 规划完整性判断读取新字段。

## 非目标

- 不修改前端 API 类型和页面。
- 不自动执行生产数据库 DDL。
- 不保留旧接口、旧实体或旧字段兼容层。
- 不实现全局画像的完整自动聚合算法。
- 不实现 JSON 数组按业务主键合并。
- 不让大模型生成或猜测 `subjectId`。
- 不改变异步画像更新不阻塞主回答的架构。
