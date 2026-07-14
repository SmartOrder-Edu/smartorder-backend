# Auth0 多平台第三方登录设计

## 文档状态

- 日期：2026-07-13
- 状态：整体设计已确认；正式文档待用户进行文件级复核
- 适用工程：smartorder-backend、smartorder-frontend、Auth0 Tenant
- 首批平台：GitHub、Google、Gitee、百度

## 1. 背景

SmartOrder 当前使用本地用户表、JWT access token、Redis refresh token 和 MVC LoginInterceptor 完成登录态管理。后端 smartorder-common 已存在一套手写 GitHub OAuth 登录，前端也有 GitHub 专用按钮、路由和回调页。

现有实现不适合继续复制到多个平台：

- user 表直接保存 github_id 和 github_username；继续增加平台会造成字段膨胀。
- AuthController、AuthServiceImpl、GithubServiceImpl 和前端回调均与 GitHub 强耦合。
- 前端未生成 state，后端也没有验证 state。
- GitHub 调用失败或配置缺失时会降级为固定 Mock 用户，存在认证绕过风险。
- 当前逻辑会按第三方邮箱静默绑定已有账号，但没有可靠验证邮箱归属。
- 授权码被写入日志，refresh-token Cookie 缺少完整的生产安全属性。

本设计将所有外部平台接入统一收敛到 Auth0。SmartOrder 只信任 Auth0 OIDC，并继续签发自己的 JWT，因此不用重写现有业务接口和 Agent、学习、测评等模块的鉴权逻辑。

## 2. 目标

1. GitHub、Google、Gitee、百度全部通过 Auth0 登录。
2. 平台扩展不再修改 user 表或复制 Controller、Service 和回调页面。
3. 同一已验证邮箱自动归并为一个 SmartOrder 本地用户。
4. 平台没有可信邮箱时，要求用户完成本地邮箱验证码，再创建或合并账号。
5. 保留现有 SmartOrder JWT、Redis refresh token 和 UserRegisteredEvent。
6. 安全处理 state、nonce、PKCE、OIDC Token、重放、并发回调和开放重定向。
7. 平滑迁移现有 GitHub 用户，并能通过配置开关快速回滚。

## 3. 非目标

- 本期不使用第三方 access token 调用 GitHub、Google、Gitee 或百度业务 API。
- 本期不保存第三方 access token、refresh token 或 Client Secret 到业务数据库。
- 本期不把全项目鉴权改成直接消费 Auth0 access token。
- 本期不实现企业多租户、SAML、组织级连接或 Auth0 Organizations。
- 本期不自动调用 Auth0 Management API 合并 Auth0 用户；多个 Auth0 subject 可以映射到同一个 SmartOrder user。
- 本期不重构 access token 在前端 localStorage 中的现有存储方式。
- 本期不为管理员实现第三方身份绑定；管理员继续使用现有本地认证方式。

## 4. 核心决策

### 4.1 统一身份网关

所有平台都先进入 Auth0。GitHub、Google 使用 Auth0 内建 Social Connection；Gitee、百度优先使用 Custom Social Connection。

SmartOrder 后端使用 Spring Security OAuth2 Client 与 Auth0 建立 OIDC Authorization Code Flow。Spring Security 只负责临时 OIDC 登录流程和 Token 验证，登录成功后仍由 SmartOrder 签发本地 access token 与 refresh token。

### 4.2 自动合并规则

只有可信邮箱才能触发自动合并：

- Auth0 明确返回 email_verified=true：允许按邮箱自动合并。
- Gitee 或百度仅返回邮箱但没有可靠验证标志：不允许自动合并。
- 没有邮箱或邮箱不可信：进入本地邮箱 OTP 流程。
- 管理员账号不参与第三方邮箱自动合并，也不在本期开放第三方身份绑定；邮箱命中管理员时拒绝 OAuth 登录并提示使用原登录方式。

已经建立的 issuer + auth0_subject 映射永远优先于邮箱。后续第三方邮箱变化只能更新账号资料，不能把身份迁移给另一个本地用户。

### 4.3 三层数据模型

采用以下关系：

user 1 对多 user_auth_account 1 对多 user_auth_identity

- user：SmartOrder 业务用户。
- user_auth_account：一个 Auth0 用户主体，即一个 OIDC issuer + sub。
- user_auth_identity：Auth0 账号内的一条具体平台身份。

这种结构同时支持：

- 一个本地用户拥有多个未在 Auth0 内合并的 Auth0 subject。
- 一个 Auth0 用户通过 Auth0 Account Linking 拥有多个平台身份。
- Gitee、百度等自定义 Connection。
- 未来新增 Microsoft、Apple、企业微信等平台而不修改 user 表。

## 5. 数据模型

### 5.1 user 表调整

保留现有字段和业务语义，只做增量调整：

| 字段 | 类型与约束 | 说明 |
| --- | --- | --- |
| email | VARCHAR(255) NULL，保留唯一索引 | 从现有 VARCHAR(100) 扩容，作为规范邮箱与自动合并键 |
| email_verified | TINYINT NOT NULL DEFAULT 0 | 本地规范邮箱是否已经通过可信渠道验证 |
| password | VARCHAR(255) NULL | 允许纯第三方用户没有本地密码 |

已有 email 唯一索引继续作为本地账号合并约束。邮箱匹配只进行 trim 和统一小写，不折叠 Gmail 点号，也不删除加号别名。

github_id、github_username 和 uk_github_id 在首次上线时保留，仅用于旧用户迁移；稳定期结束后在独立迁移中删除。

password 为空的用户使用密码登录时统一返回现有“账号或密码错误”，不暴露账号来源。用户以后可通过已经验证的邮箱和现有密码设置或找回流程建立本地密码。

### 5.2 user_auth_account

一行代表一个 Auth0 OIDC 主体。

| 字段 | 类型与约束 | 说明 |
| --- | --- | --- |
| id | BIGINT PRIMARY KEY AUTO_INCREMENT | 主键 |
| user_id | BIGINT NOT NULL | 逻辑关联 user.id |
| issuer | VARCHAR(191) NOT NULL | Auth0 OIDC issuer，保存规范化值 |
| auth0_subject | VARCHAR(191) NOT NULL | ID Token 的 sub |
| current_email | VARCHAR(255) NULL | 本次身份流程使用的规范邮箱 |
| email_verified | TINYINT NOT NULL DEFAULT 0 | current_email 是否可信 |
| email_verification_source | VARCHAR(32) NULL | AUTH0 或 LOCAL_OTP |
| link_reason | VARCHAR(32) NOT NULL | NEW_USER、VERIFIED_EMAIL、LOCAL_OTP、LEGACY_GITHUB_ID |
| display_name | VARCHAR(100) NULL | Auth0 展示名称 |
| picture_url | VARCHAR(500) NULL | Auth0 头像 |
| status | TINYINT NOT NULL DEFAULT 1 | 0 禁用，1 正常 |
| first_login_time | DATETIME NOT NULL | 首次登录时间 |
| last_login_time | DATETIME NOT NULL | 最近登录时间 |
| create_time | DATETIME NOT NULL | 创建时间 |
| update_time | DATETIME NOT NULL | 更新时间 |

索引与约束：

- 唯一索引 uk_issuer_subject：issuer + auth0_subject。
- 普通索引 idx_user_id：user_id。
- 普通索引 idx_current_email：current_email。

本项目当前数据库脚本普遍不使用物理外键，因此本表也使用逻辑关联、索引和服务事务，不增加数据库外键。

### 5.3 user_auth_identity

一行代表一个平台身份。

| 字段 | 类型与约束 | 说明 |
| --- | --- | --- |
| id | BIGINT PRIMARY KEY AUTO_INCREMENT | 主键 |
| auth_account_id | BIGINT NOT NULL | 逻辑关联 user_auth_account.id |
| provider_code | VARCHAR(32) NOT NULL | github、google、gitee、baidu |
| connection_name | VARCHAR(100) NOT NULL | Auth0 Connection 名称 |
| strategy | VARCHAR(100) NULL | Auth0 实际策略，仅作观测，不参与业务分支 |
| provider_subject | VARCHAR(191) NOT NULL | 平台在当前 Connection 下的稳定用户 ID |
| provider_username | VARCHAR(100) NULL | 平台展示用户名 |
| provider_email | VARCHAR(255) NULL | 平台原始邮箱 |
| email_verified | TINYINT NOT NULL DEFAULT 0 | 平台是否明确证明邮箱已验证 |
| last_login_time | DATETIME NOT NULL | 最近使用时间 |
| create_time | DATETIME NOT NULL | 创建时间 |
| update_time | DATETIME NOT NULL | 更新时间 |

索引与约束：

- 唯一索引 uk_connection_subject：connection_name + provider_subject。
- 普通索引 idx_auth_account_id：auth_account_id。
- 普通索引 idx_provider_code：provider_code。

首版假定每个部署环境只使用一个 Auth0 Tenant。若未来一个数据库接入多个 Tenant，唯一索引需要增加 tenant_key。

### 5.4 临时状态

OAuth 授权上下文、待补充邮箱流程和一次性登录交换凭证全部保存在 Redis，不建临时数据库表：

| Redis 对象 | 有效期 | 特性 |
| --- | --- | --- |
| Spring authorization request | 5 分钟 | 保存 Spring Security 完成 state、nonce 和 PKCE 校验所需的 OAuth2AuthorizationRequest，由框架回调处理原子读取并删除 |
| OAuth business context | 5 分钟 | 与同一 state 关联，保存 provider、站内 redirect、traceId 和 Flow CSRF 摘要，由成功或失败 Handler 二选一消费 |
| Pending email flow | 10 分钟 | 保存已验证的 Auth0 claims 和平台身份，不保存第三方 token |
| OAuth exchange ticket | 1 分钟 | 只能兑换一次 SmartOrder JWT |

Spring authorization request 和 OAuth business context 使用不同 Redis key；它们不能由两个组件竞争消费同一条记录。Redis 值不直接暴露给 JavaScript。浏览器仅持有短期 HttpOnly 流程 Cookie。

## 6. 后端架构

### 6.1 组件边界

- OAuthProviderCatalog：维护 provider_code 到 Auth0 connection_name 的白名单映射和启用状态。
- RedisOAuth2AuthorizationRequestRepository：只保存 Spring OAuth2AuthorizationRequest，并由 Spring Security 在 callback 认证阶段原子读取和删除。
- OAuthBusinessContextService：使用独立 Redis key 保存 provider、安全 redirect、traceId 和 Flow CSRF 摘要；由 Success Handler 或 Failure Handler 恰好消费一次。
- Auth0OidcSuccessHandler：接收 Spring Security 已验证的 OidcUser，调用统一登录服务。
- Auth0OidcFailureHandler：处理授权取消、state 或 nonce 失败、Token 交换失败和 OIDC 校验失败，生成最小 FAILED Flow 并跳转固定前端回调地址。
- FederatedLoginService：编排既有主体匹配、旧 GitHub 匹配、邮箱合并、建号和身份同步。
- UserAuthAccountService：负责 Auth0 主体持久化与锁定查询。
- UserAuthIdentityService：负责平台身份幂等同步。
- OAuthPendingEmailService：管理补邮箱流程和本地 OTP。
- OAuthExchangeTicketService：管理一次性本地 JWT 兑换凭证。
- TokenService：从 AuthServiceImpl 提取现有 SmartOrder 双 Token 生成和 Redis refresh-token 写入，供密码登录与 OAuth 登录复用。

OAuth 编排不得继续堆入 AuthServiceImpl，也不得为每个平台复制 Service。

### 6.2 Auth0 Action Claim

Auth0 Post-Login Action 添加一个轻量、命名空间化的 ID Token Claim，只包含：

- connection：event.connection.name。
- provider：SmartOrder 平台代码。
- user_id：从 event.user.identities 中选择 connection 与本次 event.connection.name 相同的身份 ID。

不能默认取 identities 数组第一项，因为第一项是主身份，不一定是本次登录 Connection。Claim 不包含第三方 access token、refresh token 或完整 profile。

OIDC 的 iss + sub 是本地账号绑定主键；Action Claim 只用于同步 user_auth_identity。平台身份按实际使用的 Connection 懒同步：某个已关联身份只有在被用于登录后才要求出现在本地 identity 表中，不需要每次调用 Auth0 Management API 拉取完整 identities 数组。

### 6.3 登录流程

1. 前端请求 GET /auth/oauth/{provider}/authorize，并携带站内相对 redirect。
2. 后端校验 provider 白名单和 redirect，生成 state、nonce、PKCE S256，并把上下文写入 Redis。
3. 后端跳转 Auth0 /authorize，并通过 connection 指定 GitHub、Google、Gitee 或百度。
4. 第三方完成授权后先回调 Auth0，Auth0 再回调 Spring OIDC 地址。
5. Spring Security 完成 code 换 Token、签名、issuer、audience 和 nonce 验证。
6. Spring Security 在认证阶段原子读取并删除 Spring authorization request；认证成功后，Success Handler 再一次性消费独立的 OAuth business context 并调用 FederatedLoginService。
7. READY 时后端生成有效期 1 分钟的 HttpOnly exchange Cookie；EMAIL_REQUIRED 时生成覆盖 Pending Flow 10 分钟有效期的 HttpOnly pending Cookie，然后跳回前端 /oauth/callback。
8. 前端读取流程状态；READY 时兑换 SmartOrder JWT，EMAIL_REQUIRED 时进入邮箱 OTP。
9. JWT 兑换成功后设置 refresh-token Cookie，返回本地 access token，并恢复安全的原始站内 redirect。

Authorization Code Flow 按协议会把 code 和 state 放在 Auth0 到后端的 callback URL 中；该 callback 必须禁止访问日志记录 query。authorization code 不得进入前端 URL、业务重定向 URL或持久化存储，Auth0 ID Token、Auth0 access token 和 SmartOrder access token 不得出现在任何跳转 URL 中。

认证失败时 Failure Handler 消费独立的 OAuth business context，写入有效期 1 分钟的 FAILED Flow Cookie，并跳转固定配置的前端 /oauth/callback。若 state 被篡改而无法找到业务上下文，只能使用固定前端地址和通用 OAUTH_CALLBACK_FAILED，不能采用请求参数中的 redirect。

### 6.4 自动合并事务

FederatedLoginService 在单个事务中执行：

1. 按 issuer + auth0_subject 查询 user_auth_account。
2. 已存在时直接使用已绑定 user_id；不再按邮箱重新决定归属。
3. 不存在时，若当前身份是 GitHub，先按旧 user.github_id 匹配历史用户。
4. 旧 ID 未命中时，仅对可信邮箱执行本地 user.email 匹配。
5. 邮箱命中普通启用用户时绑定该 user；管理员或禁用用户拒绝自动合并。
6. 邮箱未命中时创建 user，password 为空，默认角色为学生。
7. 插入 user_auth_account 并幂等插入或更新 user_auth_identity。
8. 仅在真正创建新 user 时登记一次 UserRegisteredEvent，并在事务成功提交后投递；回滚事务不得初始化学生画像。
9. 事务提交后才允许生成登录交换凭证。

创建新 user 时使用规范邮箱并设置 email_verified=1；用户名优先取 Auth0 display name，清洗到现有 2 至 20 字符约束后，在冲突时追加稳定短后缀并依靠 uk_username 重试一次。没有可用名称时使用 provider_code 加短随机后缀。头像只用于初始化新用户；合并到已有 user 时，不用第三方名称或头像覆盖用户已经维护的本地资料。

并发安全同时依赖：

- user.email 唯一索引。
- user_auth_account 的 issuer + auth0_subject 唯一索引。
- user_auth_identity 的 connection_name + provider_subject 唯一索引。
- 对已有邮箱用户执行锁定查询。
- 插入遇到 DuplicateKeyException 时重新读取唯一记录并验证归属，而不是盲目重试建号。

### 6.5 无可信邮箱流程

EMAIL_REQUIRED 只适用于尚未建立 issuer + auth0_subject 映射、也未命中旧 GitHub ID 的首次登录。已经存在主体映射时直接使用原 user_id，不因本次 Gitee、百度或其他平台缺少 email_verified 而重复要求 OTP。

首次登录的 Gitee、百度，或任何未提供 email_verified=true 的平台身份，进入 EMAIL_REQUIRED：

1. 已验证的 Auth0 claims 和平台身份写入 Redis Pending Flow。
2. 用户填写邮箱。
3. 后端按现有邮件基础设施发送验证码，并对 IP、邮箱、Flow 三个维度限流。
4. 用户提交验证码。
5. 验证成功后设置 email_verification_source=LOCAL_OTP，并执行同一个自动合并事务。
6. Pending Flow 和验证码一次性消费。

第三方仅返回了一个邮箱字符串时，不能绕过本地 OTP。

## 7. API 契约

所有 JSON API 继续使用项目现有 Result<T> 包装。

### 7.1 公开接口

- GET /auth/oauth/providers
  - 返回启用平台的 code、displayName 和 iconKey，不返回 Connection Secret 或平台 Client ID。
- GET /auth/oauth/{provider}/authorize?redirect=/dashboard
  - 校验 provider 和 redirect 后返回 302。
- GET /login/oauth2/code/auth0
  - Spring Security 内部回调，只允许 Auth0 流程使用。
- GET /auth/oauth/session
  - 依据 HttpOnly 临时流程 Cookie 返回 READY、EMAIL_REQUIRED、FAILED 或 EXPIRED，以及 provider、安全 redirect 和本次 Flow 的 CSRF token。
- POST /auth/oauth/exchange
  - READY 状态下一次性兑换 SmartOrder access token；必须携带 X-OAuth-Flow-CSRF；响应同时设置 refresh-token Cookie。
- POST /auth/oauth/pending-email/send
  - 请求体只包含 email；Flow 身份来自 HttpOnly Cookie，并必须携带 X-OAuth-Flow-CSRF。
- POST /auth/oauth/pending-email/verify
  - 请求体包含 email 和 verificationCode，并必须携带 X-OAuth-Flow-CSRF；成功后消费 Pending Flow、签发新的 1 分钟 exchange Cookie，并把状态切换为 READY。

### 7.2 错误模型

对前端返回稳定错误码，不透传 Auth0 或第三方原始错误：

- OAUTH_PROVIDER_DISABLED
- OAUTH_STATE_INVALID
- OAUTH_CALLBACK_FAILED
- OAUTH_SESSION_EXPIRED
- OAUTH_EMAIL_REQUIRED
- OAUTH_EMAIL_CONFLICT
- OAUTH_ACCOUNT_DISABLED
- OAUTH_ADMIN_OAUTH_DISABLED
- OAUTH_TICKET_CONSUMED

服务日志使用 traceId 关联排障，不记录授权码、Token、验证码、完整邮箱或 Client Secret。

## 8. 前端设计

### 8.1 页面与路由

- Login/index.vue：通过平台列表渲染按钮，不再保存或拼接 GitHub 授权配置。
- OAuthCallback.vue：通用回调状态页，处理 READY、EMAIL_REQUIRED、FAILED、EXPIRED。
- OAuthEmailCompletion.vue：邮箱输入、验证码发送和验证组件。
- 路由 /oauth/callback：不设置会导致已有 token 提前跳走的 guest 逻辑。

现有 GithubCallback.vue 在迁移完成后删除。当前前端工作区中 Login/index.vue 与 GithubCallback.vue 已有未提交的用户改动，实施时必须基于当前内容迁移，不能覆盖或还原。

### 8.2 API 与 Store

userApi 删除 githubLogin，增加：

- getOAuthProviders
- getOAuthSession
- exchangeOAuthLogin
- sendOAuthEmailCode
- verifyOAuthEmail

Pinia user store 增加通用 OAuth action；成功后继续复用 setToken、fetchUserInfo 和现有画像初始化路由。

前端不保存 Auth0 Client Secret、平台 Client Secret、Auth0 ID Token 或第三方 access token。前端也不再需要 VITE_GITHUB_CLIENT_ID。

## 9. Auth0 与平台配置

### 9.1 Auth0 Application

- 类型：Regular Web Application。
- SmartOrder Callback URL：后端 /login/oauth2/code/auth0。
- Allowed Logout URL：SmartOrder 前端配置的退出落点。
- GitHub、Google、Gitee、百度 Connection 仅对本 Application 启用。
- Auth0 Domain、Client ID、Client Secret 通过后端环境变量配置。

完整退出清理 SmartOrder refresh token 和 Auth0 会话，但不尝试注销用户在 GitHub、Google、Gitee 或百度站点本身的会话。

### 9.2 两层回调

- Gitee、百度平台登记的回调：Auth0 的 https://{auth0-domain}/login/callback。
- Auth0 Application 登记的回调：SmartOrder 后端的 /login/oauth2/code/auth0。

平台不能直接回调 Vue 页面或旧 /auth/github/login。

### 9.3 平台矩阵

| provider_code | Auth0 Connection | 稳定平台 ID | 邮箱策略 |
| --- | --- | --- | --- |
| github | Auth0 内建 GitHub | GitHub 用户 ID | 仅信任 Auth0 明确给出的已验证邮箱 |
| google | Auth0 内建 Google | Google subject | 使用 Auth0 email_verified |
| gitee | Custom Social | Gitee 用户 id | 默认不可信，走 LOCAL_OTP |
| baidu | Custom Social 或协议适配 | 百度 openid | 用户资料无可靠邮箱，走 LOCAL_OTP |

Gitee Custom Social 使用 Gitee OAuth authorize、token 和 /api/v5/user，并把稳定 id 映射成 Auth0 user_id。

百度 Custom Social 使用百度 authorize、token 和 passport users getInfo。provider_subject 使用必返 openid，不使用逐步废弃的 userid。unionid 只在未来确有跨百度应用统一身份需求时考虑。

### 9.4 百度兼容性门禁

百度官方当前文档明确展示 GET 方式交换 Token，而 Auth0 Custom Social Connection 与该行为的完整兼容性需要实测。正式主体开发前必须完成 PoC：

1. Auth0 能否使用百度 code 换取 Token。
2. Fetch User Profile 能否取得 openid。
3. Auth0 能否返回规范化用户并完成 SmartOrder OIDC 回调。
4. 错误和取消授权是否能稳定映射。

如果 PoC 失败，保留所有平台仍通过 Auth0 的原则，但在 Auth0 与百度之间增加最小 OAuth 协议适配端点。适配层只转换百度所需请求格式，不承担 SmartOrder 本地用户登录。

## 10. 安全设计

- Authorization Code Flow 使用 state、nonce 和 PKCE S256。
- Redis 授权上下文和 ticket 都一次性消费并限制 TTL。
- provider 参数通过后端白名单映射，不能由客户端传任意 Auth0 Connection。
- Action Claim 中的 provider、connection 和 user_id 必须再次与 OAuthProviderCatalog 映射校验；未知或不一致的 Claim 拒绝登录。
- redirect 只允许站内相对路径，不允许协议、主机、双斜线或反斜线变体。
- OIDC 严格校验签名、issuer、audience、nonce、时间声明和算法。
- Auth0 和第三方不可用时 fail closed，禁止 Mock 或匿名降级。
- refresh-token Cookie 在生产设置 HttpOnly、Secure、SameSite=Lax、Path=/auth；若前后端跨站部署，则显式改为 SameSite=None + Secure。
- READY、FAILED 使用的 exchange Flow Cookie 设置 HttpOnly、Secure、SameSite=Lax、Path=/auth/oauth，最大有效期 1 分钟。
- EMAIL_REQUIRED 使用独立 pending Flow Cookie，安全属性相同但最大有效期 10 分钟；OTP 成功后立即删除 pending Cookie并轮换成 1 分钟 exchange Cookie。
- GET /auth/oauth/session 返回随机 Flow CSRF token；exchange、OTP 发送和 OTP 验证必须通过 X-OAuth-Flow-CSRF 回传并与 Redis 摘要恒定时间比较。
- 所有 Flow POST 接口同时校验严格的前端 Origin allowlist，并使用仅允许该 Origin、allowCredentials=true 的 CORS 配置；缺失或不匹配 Origin 时拒绝请求。该规则在 SameSite=None 的跨站部署下仍然生效。
- 管理员账号不按邮箱自动合并。
- disabled user、disabled auth account 和 disabled provider 均拒绝登录。
- 发送与验证邮箱 OTP 按 IP、邮箱和 Flow 限流，并限制错误次数。
- 日志和监控标签不包含 PII 或凭证。
- Auth0 Action 自定义 Claim 使用自有命名空间并保持最小负载。

## 11. 迁移与上线

### 11.1 阶段一：PoC

在任何灰度工作之前先对旧 GitHub 直连做安全前置修复：删除生产 Mock 和任意异常自动降级、删除授权码日志、配置缺失时 fail closed。旧真实 GitHub 直连暂时保留，但不再允许模拟身份绕过认证。

随后验证 GitHub、Google、Gitee 和百度 Connection，百度 PoC 是进入主体开发的门禁。

### 11.2 阶段二：增量数据库迁移

- 新建 user_auth_account 和 user_auth_identity。
- user.email 从 VARCHAR(100) 扩容到 VARCHAR(255)，保留唯一索引。
- user 新增 email_verified。
- user.password 改为可空。
- 保留所有旧 GitHub 字段和接口。
- 上线前审计邮箱大小写归一化冲突、重复 github_id、空邮箱、管理员和禁用用户。

历史本地注册用户只有在能证明其注册流程经过现有邮件验证码时才能批量回填 email_verified=1；来源不确定的记录保持 0。

### 11.3 阶段三：历史 GitHub 首次登录迁移

旧用户没有 Auth0 sub，因此不伪造 user_auth_account。第一次通过 Auth0 GitHub 登录时按以下顺序匹配：

1. issuer + auth0_subject。
2. 旧 github_id。
3. 已验证邮箱。
4. 新建用户。

旧 github_id 命中时记录 link_reason=LEGACY_GITHUB_ID。

### 11.4 阶段四：双链路灰度

- 提供 Auth0 总开关和每个平台独立开关。
- 保留旧密码登录和已经完成安全修复的旧 GitHub 真实入口；通用入口与旧入口并存，只在此阶段支持开关回滚。
- 先内部账号，再小流量，再全量。
- 观察成功率、失败阶段、自动合并量、OTP 完成率、唯一索引冲突和历史 GitHub 迁移数。

### 11.5 阶段五：正式切换和清理

稳定运行并确认历史迁移完成后：

1. 关闭旧 /auth/github/login。
2. 删除剩余 GitHub 专用 Controller、Service、DTO、VO 和 Properties；Mock 与异常降级已在阶段一删除。
3. 删除前后端 GitHub 直连配置。
4. 在独立迁移中删除 github_id、github_username 和旧唯一索引。

旧字段删除绝不与首次上线合并在同一个发布中。

### 11.6 回滚

- 阶段四清理前：关闭 Auth0 总开关或单平台开关，流量回到已修复的旧密码登录和旧 GitHub 真实入口。
- 阶段五清理后：不再承诺仅靠开关恢复旧 GitHub；需要回滚到前一应用版本。数据库增量结构保持向后兼容，因此不做逆向删除。
- 新表和新字段保留，不执行逆向删除。
- 已签发 SmartOrder JWT 继续有效。
- 暂停新身份写入，修复后继续灰度。

回滚不会删除新用户或身份数据。Google、Gitee 和百度在 Auth0 关闭期间暂时不可登录，这是明确接受的降级结果。

## 12. 测试策略

### 12.1 后端单元测试

- 已存在 issuer + sub 时映射优先，邮箱变化不转移账号。
- 新身份通过可信邮箱合并普通启用用户。
- 未验证邮箱不能自动合并。
- Gitee、百度进入 LOCAL_OTP。
- OTP 完成后创建或合并正确用户。
- 管理员和禁用用户拒绝自动合并。
- 新用户只发布一次 UserRegisteredEvent。
- 重复 callback 和重复 ticket 消费失败。
- provider 白名单和 redirect 校验覆盖常见绕过变体。
- Auth0 Action Claim 按当前 connection 匹配身份，而不是默认第一项。

### 12.2 集成与并发测试

- 使用可控的 Auth0 OIDC Stub 或 WireMock 验证 discovery、JWKS、token 和 claim 场景。
- 使用真实 MySQL 兼容环境验证唯一索引和事务。
- 两个相同 callback 并发时只创建一个 user_auth_account 和 user_auth_identity。
- 两个不同平台用同一可信邮箱并发首次登录时只创建一个 user。
- Spring authorization request、独立 business context、Pending Flow 和 exchange ticket 分别验证 TTL、所有权和一次性消费。
- Success Handler 与 Failure Handler 竞争同一个 business context 时只有一个能够完成 Flow。
- Flow CSRF header、Origin allowlist 和 credentialed CORS 覆盖同站与跨站部署。

### 12.3 前端测试

- 四个平台按钮按启用配置渲染。
- 通用回调覆盖 READY、EMAIL_REQUIRED、FAILED、EXPIRED。
- OAuth code 和 state 只出现在 Auth0 到后端的协议 callback；前端业务 URL、localStorage 临时键和日志中不出现 code 或任何 Token。
- 原始 redirect 安全恢复。
- 已存在或过期的本地 token 不会让回调路由提前跳走。

### 12.4 端到端验收

- GitHub、Google 新用户登录并初始化学生画像。
- 相同已验证邮箱跨平台归并为同一 user_id。
- Gitee、百度完成本地邮箱 OTP 后登录。
- 历史 GitHub 用户首次 Auth0 登录不生成重复用户。
- refresh token、访问令牌刷新、WebSocket 和用户信息加载正常。
- 授权取消、Auth0 故障、错误签名和重复回调均不会得到 SmartOrder JWT。

## 13. 可观测性

记录不含 PII 的指标：

- oauth_login_started_total，按 provider 分类。
- oauth_login_success_total，按 provider 和 outcome 分类。
- oauth_login_failure_total，按 provider 和 stage 分类。
- oauth_email_merge_total。
- oauth_local_otp_required_total 与 oauth_local_otp_completed_total。
- oauth_legacy_github_migrated_total。
- oauth_duplicate_key_conflict_total。
- oauth_callback_duration。

每次认证 Flow 使用随机 traceId，贯穿授权开始、回调、合并事务和 JWT 兑换。告警关注 Auth0 回调失败率、百度或 Gitee 单平台异常、OTP 完成率突降以及唯一键冲突突增。

## 14. 受影响文件边界

后端主要修改 smartorder-common：

- pom.xml：增加 Spring Security OAuth2 Client 依赖。
- config/SecurityConfig.java：加入 Auth0 OIDC 登录链路但保留现有业务鉴权边界。
- config/WebConfig.java：放行统一 OAuth 入口和回调，不再逐平台追加路径。
- controller/AuthController.java：灰度阶段增加通用入口并保留已修复的 GitHub 专用入口；清理阶段再移除旧入口。
- service/impl/AuthServiceImpl.java：先抽取 TokenService 并保留安全的旧 GitHub 编排用于灰度回滚；清理阶段再删除 GitHub 编排。
- 新增 OAuth 配置、实体、Mapper、服务、Success Handler、Failure Handler、Redis Repository 和测试。
- resources/sql：新增建表与迁移脚本。
- resources/application-common.yml：阶段一立即删除生产 Mock 默认值并令旧直连配置缺失时 fail closed；随后增加 Auth0 与平台开关，清理阶段再删除旧 GitHub 配置。

前端主要修改：

- src/views/Login/index.vue。
- src/views/Login/GithubCallback.vue：灰度期保留旧回调；清理阶段由 OAuthCallback.vue 完全替代。
- src/views/Login/OAuthEmailCompletion.vue。
- src/api/user.ts。
- src/stores/user.ts。
- src/router/index.ts。
- src/config/oauth.ts。
- vite.config.ts 和环境配置：阶段一删除 MOCK 回退，灰度期保留真实 GitHub 直连配置，清理阶段再删除。

smartorder-agent、smartorder-student、smartorder-learning 和 smartorder-assessment 不承担 OAuth 平台逻辑。smartorder-student 只通过既有 UserRegisteredEvent 感知真正的新用户。

## 15. 验收标准

1. 四个平台都从 Auth0 进入 SmartOrder，不存在前端直连平台授权实现。
2. 一个本地 user 可以安全关联多个 Auth0 account 和多个 provider identity。
3. 相同可信邮箱自动合并；无可信邮箱必须完成本地 OTP。
4. 历史 GitHub 用户迁移后 user_id、学习画像和业务数据保持不变。
5. 所有 OAuth 临时状态都短期且一次性；authorization code 只经过后端协议 callback 且不记录，任何 Token 和 Flow ticket 都不进入 URL 或日志。
6. 并发首次登录不会产生重复 user、auth account 或 identity。
7. 新用户只触发一次 UserRegisteredEvent。
8. Auth0 或平台故障时拒绝登录，不存在 Mock 降级。
9. 清理前可通过配置开关按平台灰度和回滚；清理后通过前一兼容应用版本回滚且不逆向删除数据库结构。
10. 百度 PoC 结论在主体实现前明确；若需协议适配，适配范围只位于 Auth0 与百度之间。

## 16. 参考资料

- Auth0 Spring Boot Quickstart：https://auth0.com/docs/quickstart/webapp/java-spring-boot
- Auth0 Custom Social Connection：https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/oauth2
- Auth0 Social Login API：https://auth0.com/docs/api/authentication/login
- Auth0 Custom Claims：https://auth0.com/docs/secure/tokens/json-web-tokens/create-custom-claims
- Auth0 Account Linking：https://auth0.com/docs/manage-users/user-accounts/user-account-linking
- Gitee OAuth：https://gitee.com/api/v5/oauth_doc
- 百度 OAuth：https://openauth.baidu.com/doc/doc.html
- 百度 OpenID 与 UnionID：https://openauth.baidu.com/doc/openid.html
