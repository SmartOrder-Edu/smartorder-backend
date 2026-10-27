# smartorder-agent 包结构约定

本模块按典型 Spring Boot 分层组织：**controller / service / service.impl / mapper / model / config / exception / util**，
外加两个纵向内聚子模块 **rag**、**tool**。新增代码请按下表归位，不要再新建「功能名 + 平铺类」的包。

## 目录

| 包 | 放什么 | 当前内容 |
| --- | --- | --- |
| `controller/` | REST 接口，只做参数校验、鉴权、协议转换 | 9 个 `*Controller`；对话任务入口为 `AgentTaskController`，旧路径 `/agent/generations` 作为 deprecated 别名 |
| `service/` | 业务接口 + 无接口的业务服务 | 14 个接口（`AgentChatService`、`ClarificationService`…）+ 9 个业务服务（`ConversationMessageService` 等） |
| `service/impl/` | 接口实现，以及仅供 service 层使用的内部协作类 | 9 个 `*ServiceImpl` + 编排引擎/校验器/路由策略（`AgentOrchestratorImpl`、`PlanAndExecuteEngine`、`StateRuleRouter`…） |
| `mapper/` | MyBatis-Plus `BaseMapper`，只做表 CRUD | 11 个 Mapper；`AgentTaskRecordMapper` 负责 `agent_chat_task` |
| `model/entity/` | 数据库实体 | 11 个；`AgentTaskRecord` 对应 `agent_chat_task` |
| `model/dto/` | 入参与内部传输对象 | 19 个 |
| `model/vo/` | 出参视图对象 | 10 个；`AgentTaskVO` 是任务对外视图 |
| `model/enums/` | 枚举 | `AgentEventType`、`AgentTaskStatus`、`AgentRunMode`、`IntentType`、`RoutingSource` |
| `config/` | `@Configuration` / `@ConfigurationProperties` | `AgentConfiguration`、`AgentExecutorConfig`、`AgentSseProperties` |
| `exception/` | 业务异常 | `AgentTaskException`、`BudgetExceededException`、`AttachmentNotReadyException` |
| `constant/` | 协议常量 | `AgentEventNames` |
| `util/` | 无状态工具与加载器 | `PromptLoader`、`TitleNormalizer`、`TitleInputExtractor`、`ConfirmationWords`、`AgentStreamCursor` |
| `runtime/` | **对话任务运行时内核**：状态聚合、事件、订阅、SSE 出口、执行预算 | `AgentTask`（本机执行句柄）、`AgentTaskState`（Redis 状态快照）、`AgentTaskRegistry`、`AgentTaskEvent(Meta/Subscription)`、`AgentEventSink`、`SseEmitterEventSink`、`AgentTaskEventMirror`、`ExecutionBudget` |
| `runtime/store/` | **任务非表存储**：事件归档 + 运行时状态 | `AgentTaskEventStore` + `{Redis,InMemory}AgentTaskEventStore`（Redis Stream）、`AgentTaskStateStore` + `{Redis,InMemory}AgentTaskStateStore`（状态/取消/中断/节点租约） |
| `repository/` | **非表数据访问接口** + MySQL 任务仓储 | `AgentTaskEventStore` + `impl/{Redis,InMemory}AgentTaskEventStore`（Redis Stream）、`AgentTaskRepository` + `impl/MyBatisAgentTaskRepository` |
| `task/` | 定时任务 | `InterruptedReplyRecovery`（从 Redis Stream 恢复中断正文并收敛任务） |
| `rag/` | RAG 子模块（检索/重排/评测，保持纵向内聚） | 26 个类 |
| `tool/` | Agent 工具与注册表（保持纵向内聚） | 32 个类 |

## 分层规则

1. **依赖方向**：`controller → service → mapper / repository / runtime`。
   `model`、`exception`、`constant`、`util` 可被任意层依赖；`service.impl` 不出现在 controller 的 import 里
   （无接口的业务服务放 `service/`，例如 `PlanDraftService`）。
2. **接口与实现**：对外有多个调用方的业务服务写成「`service/X` 接口 + `service/impl/XImpl` 实现」，
   注解（`@Service`、`@Async`、`@Transactional`）只写在实现类上。
3. **运行时对象不是实体**：对话任务的本机执行态（锁、seq 分配、订阅广播）放 `runtime/`，
   不要塞进 `model/entity`；线程安全边界与事件回放语义都由这一层负责。
   **任务状态不放本机**：`status`/`lastSeq`/绑定/取消/中断标记一律经 `AgentTaskStateStore` 落 Redis，
   本机 `Map` 只能存不可序列化的 SSE 订阅句柄。
4. **数据访问二选一**：表数据走 `mapper`（MyBatis-Plus）；Redis/内存等非表存储走 `runtime/store`。
   任务元数据属表数据，落库权威在 MySQL；Redis 存事件片段与运行时状态（活跃/中断/取消/节点租约）。
5. **`rag`、`tool` 保持纵向**：它们是自洽的子模块，内部按 analysis/retriever/rerank 等职责分包，
   不拆到全局分层里，避免把 AI 工具链切碎。

## task 命名与断线重连约定

- **一条 assistant 消息 = 一个 task**。`AgentTaskVO.taskId` 是发送接口同步返回的查询 ID；
  `agent_chat_task` 与 `conversation_message` 通过 `task_id` 关联，插入消息时由
  `ConversationMessageService.openReply(..., taskId)` 同时写入 user / assistant 两行。
- **发送与订阅分离**：`POST /agent/tasks` 先占任务行、开事务 A，返回 `taskId` 与消息 ID；
  执行线程异步跑编排。SSE 只是只读订阅，断线重连带 `after` 游标重新 GET 即可。
- **Redis Stream**：每个任务一条 `agent:task:{taskId}:events`，XADD 显式使用 `0-seq`，
  因此业务 `seq` 与 Stream ID 一一对应；`XREAD` 从 `0-{after}` 精确补发。
- **MySQL 权威**：任务状态、归属、消息关联、幂等键在 `agent_chat_task`。
  Redis Stream 过期或被清理时，状态查询仍可用；回放缺口返回 `410 CURSOR_EXPIRED`，不静默跳事件。
- **幂等**：`(user_id, task_request_id)` 唯一键是最终防线；命中未绑定消息的旧行时优先按
  `conversation_message.task_id` 重新绑定，避免崩溃窗口插出第二对消息。
- **旧接口兼容**：`/agent/generations` 与返回体里的 `generationId` 只是 deprecated 别名，
  新代码统一使用 `taskId`。

## 新增代码放哪里（示例）

- 新接口 `POST /agent/xxx` → `controller/AgentXxxController` + `service/AgentXxxService` + `service/impl/AgentXxxServiceImpl`。
- 新增一张表 → `model/entity` + `mapper/XxxMapper` + `model/vo`（对外） / `model/dto`（入参）。
- 新增一个对话任务内的并发/回放能力 → `runtime/`。
- 新增一个 Redis 缓存或事件归档 → `repository/` + `repository/impl/`。
