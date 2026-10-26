# smartorder-agent 包结构约定

本模块按典型 Spring Boot 分层组织：**controller / service / service.impl / mapper / model / config / exception / util**，
外加两个纵向内聚子模块 **rag**、**tool**。新增代码请按下表归位，不要再新建「功能名 + 平铺类」的包。

## 目录

| 包 | 放什么 | 当前内容 |
| --- | --- | --- |
| `controller/` | REST 接口，只做参数校验、鉴权、协议转换 | 9 个 `*Controller` |
| `service/` | 业务接口 + 无接口的业务服务 | 14 个接口（`AgentGenerationService`、`ClarificationService`…）+ 9 个历史业务服务（`ConversationTurnService` 等） |
| `service/impl/` | 接口实现，以及仅供 service 层使用的内部协作类 | 9 个 `*ServiceImpl` + 编排引擎/校验器/路由策略（`AgentOrchestratorImpl`、`PlanAndExecuteEngine`、`StateRuleRouter`…） |
| `mapper/` | MyBatis-Plus `BaseMapper`，只做表 CRUD | 10 个 Mapper |
| `model/entity/` | 数据库实体 | 10 个 |
| `model/dto/` | 入参与内部传输对象 | 19 个 |
| `model/vo/` | 出参视图对象 | 10 个 |
| `model/enums/` | 枚举 | `AgentEventType`、`AgentGenerationStatus`、`AgentRunMode`、`IntentType`、`RoutingSource` |
| `config/` | `@Configuration` / `@ConfigurationProperties` | `AgentConfiguration`、`AgentExecutorConfig`、`AgentSseProperties` |
| `exception/` | 业务异常 | `AgentGenerationException`、`BudgetExceededException`、`AttachmentNotReadyException` |
| `constant/` | 协议常量 | `AgentEventNames` |
| `util/` | 无状态工具与加载器 | `PromptLoader`、`TitleNormalizer`、`TitleInputExtractor`、`ConfirmationWords`、`AgentStreamCursor` |
| `runtime/` | **生成任务运行时内核**：内存聚合、事件、订阅、SSE 出口、执行预算 | `AgentGeneration`、`AgentGenerationRegistry`、`AgentGenerationEvent(Meta/Subscription)`、`AgentEventSink`、`SseEmitterEventSink`、`AgentGenerationEventMirror`、`ExecutionBudget` |
| `repository/` | **非 MyBatis 的数据访问**（Redis 事件归档等） | `AgentGenerationEventStore` + `impl/{Redis,InMemory}AgentGenerationEventStore`、`AgentChatEventLog` |
| `task/` | 定时任务 | `ConversationZombieCleaner` |
| `rag/` | RAG 子模块（检索/重排/评测，保持纵向内聚） | 26 个类 |
| `tool/` | Agent 工具与注册表（保持纵向内聚） | 32 个类 |

## 分层规则

1. **依赖方向**：`controller → service → mapper / repository / runtime`。
   `model`、`exception`、`constant`、`util` 可被任意层依赖；`service.impl` 不出现在 controller 的 import 里
   （无接口的业务服务放 `service/`，例如 `PlanDraftService`）。
2. **接口与实现**：对外有多个调用方的业务服务写成「`service/X` 接口 + `service/impl/XImpl` 实现」，
   注解（`@Service`、`@Async`、`@Transactional`）只写在实现类上。
3. **运行时对象不是实体**：生成任务的内存态（带锁、seq 分配、订阅广播）放 `runtime/`，
   不要塞进 `model/entity`；线程安全边界与事件回放语义都由这一层负责。
4. **数据访问二选一**：表数据走 `mapper`（MyBatis-Plus）；Redis/内存等非表存储走 `repository`。
5. **`rag`、`tool` 保持纵向**：它们是自洽的子模块，内部按 analysis/retriever/rerank 等职责分包，
   不拆到全局分层里，避免把 AI 工具链切碎。

## 本次结构调整中发生的变化

- 原 `generation/`（20 个平铺类）拆到 `controller`（接口层）、`service(+impl)`（应用服务）、
  `runtime`（运行时内核）、`repository`（事件归档）、`model/{dto,vo,enums}`、`config`、`constant`、`exception`。
- 原 `stream/`、`trace/`、`plan/`、`memory/`、`profile/`、`route/`、`clarification/`、`orchestrator/`、`prompt/`
  按同一规则并入全局分层；空包已删除。
- 命名修正：
  - `AgentGenerationManager` → `AgentGenerationRegistry`（它是本机运行时注册表，不是万能的 Manager）；
  - `AgentEventLog` → `AgentChatEventLog`（与生成任务的 `AgentGenerationEventStore` 区分）；
  - `PromptLoader` 归入 `util`，`AgentSseProperties` 归入 `config`。
- 9 个服务抽出接口：`AgentGenerationService`、`AgentChatTaskService`、`ConversationMemoryService`、
  `ConversationCompressService`、`ConversationTitleService`、`ClarificationService`、`PlanConfirmService`、
  `ProfilePromptService`、`IntentRouteService`。

## 新增代码放哪里（示例）

- 新接口 `POST /agent/xxx` → `controller/AgentXxxController` + `service/AgentXxxService` + `service/impl/AgentXxxServiceImpl`。
- 新增一张表 → `model/entity` + `mapper/XxxMapper` + `model/vo`（对外） / `model/dto`（入参）。
- 新增一个生成任务内的并发/回放能力 → `runtime/`。
- 新增一个 Redis 缓存或事件归档 → `repository/` + `repository/impl/`。
