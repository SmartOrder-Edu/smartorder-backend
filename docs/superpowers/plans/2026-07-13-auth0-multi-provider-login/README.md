# Auth0 多平台登录实施交接包

本目录是给其他 AI/工程师直接执行的实现入口。这里不包含业务代码改动，所有实现约束来自已确认的[设计文档](../../specs/2026-07-13-auth0-multi-provider-login-design.md)。

## 交付目标

在不改变 SmartOrder 现有业务鉴权方式的前提下，将 GitHub、Google、Gitee、QQ 及后续第三方平台统一接入 Auth0。Auth0 只负责外部身份认证；SmartOrder 后端仍签发自己的 access token 与 Redis refresh token。

账号归并规则已经锁定：

- `issuer + auth0_subject` 已存在时，永远使用已绑定的本地 `user_id`，不能因邮箱变化迁移账号。
- 只有 `email_verified=true` 的可信邮箱可以自动合并普通启用用户。
- Gitee、QQ 以及任何没有可信邮箱的平台，首次登录必须填写邮箱并完成 SmartOrder 本地验证码，再创建或合并账号。
- 管理员不开放第三方登录或自动合并，继续使用现有本地登录。
- 一个本地用户可以关联多个 Auth0 主体；一个 Auth0 主体可以观察到多个平台身份。

## 开始前必须保留的工作区状态

执行者必须先运行：

```powershell
git -C D:\develop\code\smartorder\smartorder-backend status --short --branch
git -C D:\develop\code\smartorder\smartorder-frontend status --short --branch
git -C D:\develop\code\smartorder\smartorder-frontend diff -- src/views/Login/index.vue src/views/Login/GithubCallback.vue
```

当前交接时已确认：

- 后端 Git 根目录：`D:\develop\code\smartorder\smartorder-backend`，分支 `mq`。
- 前端 Git 根目录：`D:\develop\code\smartorder\smartorder-frontend`，分支 `main`。
- 外层 `D:\develop\code\smartorder` 不是 Git 根目录。
- 前端 `src/views/Login/index.vue` 与 `src/views/Login/GithubCallback.vue` 有用户未提交修改。

执行者不得对这两份前端文件运行 `git checkout --`、`git restore`、硬重置或整文件覆盖。必须先阅读当前 diff，再把 OAuth 改动人工合并到当前内容中。

## 执行顺序与门禁

按下面顺序执行，不要并行跨越门禁：

1. [01-security-hotfix-and-provider-poc.md](01-security-hotfix-and-provider-poc.md)
   - 先关闭现有 GitHub Mock/异常降级、授权码日志和未校验 state。
   - 完成 GitHub、Google、Gitee、QQ 的 Auth0 沙箱验证。
2. [02-database-and-auth-domain.md](02-database-and-auth-domain.md)
   - 执行数据预检，添加 `user_auth_account` 与 `user_auth_identity`，建立规范化与并发约束。
   - 本阶段只做向后兼容的增量结构，不删除 GitHub 旧字段。
3. [03-backend-oidc-email-merge-and-jwt.md](03-backend-oidc-email-merge-and-jwt.md)
   - 完成 Spring Security OIDC、Redis 临时状态、可信邮箱归并、本地邮箱 OTP、一次性交换票据与本地 JWT 签发。
   - 完成单元、集成、并发与安全测试后才允许进入前端灰度。
4. [04-frontend-rollout-and-cleanup.md](04-frontend-rollout-and-cleanup.md)
   - 完成动态平台按钮、通用回调、补邮箱流程、灰度开关、监控与最终清理。
   - 旧 GitHub 直连入口和数据库旧字段只能在稳定期结束后单独清理。

如果任一门禁失败，停止后续阶段，保留已经完成的向后兼容结构并按对应计划回滚流量。

## 不可自行改变的统一契约

### 数据关系

```text
user 1 --- N user_auth_account 1 --- N user_auth_identity
```

- `user_auth_account` 的唯一业务键：`(issuer, auth0_subject)`。
- `user_auth_identity` 的唯一业务键：`(connection_name, provider_subject)`。
- 首版假设每个部署环境只有一个 Auth0 Tenant；不要提前加入多租户逻辑。
- 不保存 Auth0、GitHub、Google、Gitee、QQ 的 access token/refresh token。

### 平台目录

| provider_code | connection_name 建议值 | 可信邮箱策略                      | 稳定平台 ID    |
| ------------- | ---------------------- | --------------------------------- | -------------- |
| `github`      | `github`               | 仅接受 Auth0 明确提供的已验证邮箱 | GitHub user id |
| `google`      | `google-oauth2`        | 接受 Auth0 `email_verified=true`  | Google subject |
| `gitee`       | `gitee`                | 首次登录强制本地 OTP              | Gitee `id`     |
| `qq`          | `qq`                   | 首次登录强制本地 OTP              | QQ `openid`    |

`connection_name` 以实际 Auth0 Tenant 创建后的名称为准，但前后端只能使用后端白名单映射，不能接受客户端任意传入 connection。

### 对外 API

```text
GET  /auth/oauth/providers
GET  /auth/oauth/{provider}/authorize?redirect=/dashboard
GET  /login/oauth2/code/auth0                 # Spring Security 协议回调
GET  /auth/oauth/session
POST /auth/oauth/exchange
POST /auth/oauth/pending-email/send
POST /auth/oauth/pending-email/verify
```

所有 JSON API 继续使用现有 `Result<T>` 包装。协议回调 URL 可以包含 OAuth `code/state`，但授权码不得进入 Vue URL、业务 redirect、日志、数据库或监控标签。

### Redis 与 Cookie

| 对象                         | Redis 前缀             | TTL     | 消费方式                               |
| ---------------------------- | ---------------------- | ------- | -------------------------------------- |
| Spring authorization request | `oauth:authorization:` | 5 分钟  | Spring 回调原子读取并删除              |
| OAuth business context       | `oauth:business:`      | 5 分钟  | success/failure handler 二选一原子消费 |
| Pending email flow           | `oauth:pending:`       | 10 分钟 | OTP 成功后原子消费                     |
| Exchange/failed flow         | `oauth:exchange:`      | 1 分钟  | JWT 交换或失败状态读取时按契约消费     |

浏览器 Cookie 使用固定命名：

- `so_oauth_pending`：HttpOnly，10 分钟，`Path=/auth/oauth`。
- `so_oauth_exchange`：HttpOnly，1 分钟，`Path=/auth/oauth`。
- `refreshToken`：保留现有名称，生产环境收紧为 HttpOnly、Secure、`Path=/auth`。

同站部署使用 `SameSite=Lax`；确有跨站部署时才使用 `SameSite=None; Secure`。所有 OAuth Flow POST 同时校验 `X-OAuth-Flow-CSRF` 和严格 Origin 白名单。

### 状态与错误码

`GET /auth/oauth/session` 只返回以下状态：

```text
READY | EMAIL_REQUIRED | FAILED | EXPIRED
```

稳定错误键：

```text
OAUTH_PROVIDER_DISABLED
OAUTH_STATE_INVALID
OAUTH_CALLBACK_FAILED
OAUTH_SESSION_EXPIRED
OAUTH_EMAIL_REQUIRED
OAUTH_EMAIL_CONFLICT
OAUTH_ACCOUNT_DISABLED
OAUTH_ADMIN_OAUTH_DISABLED
OAUTH_TICKET_CONSUMED
```

不要把 Auth0 或第三方原始错误文本直接返回给前端。

## 统一配置名称

实现时使用下列环境变量，避免各计划自行命名：

```text
SMARTORDER_OAUTH_ENABLED
SMARTORDER_OAUTH_GITHUB_ENABLED
SMARTORDER_OAUTH_GOOGLE_ENABLED
SMARTORDER_OAUTH_GITEE_ENABLED
SMARTORDER_OAUTH_QQ_ENABLED
SMARTORDER_LEGACY_GITHUB_ENABLED
AUTH0_ISSUER_URI
AUTH0_CLIENT_ID
AUTH0_CLIENT_SECRET
AUTH0_FRONTEND_CALLBACK_URI
AUTH0_ALLOWED_ORIGINS
AUTH0_CLAIM_NAMESPACE
```

所有 secret 只放环境变量或部署平台密钥库，不得写入仓库、前端变量、测试快照或日志。

## 统一验证命令

后端命令从 `D:\develop\code\smartorder\smartorder-backend` 运行。PowerShell 中整个 `-D...` 参数必须加引号：

```powershell
mvn -pl smartorder-common -am '-Dtest=GithubServiceImplTest,LegacyGithubStateServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl smartorder-common -am '-Dtest=UserAuthAccountServiceTest,UserAuthIdentityServiceTest,EmailNormalizerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl smartorder-common -am '-Dtest=FederatedLoginServiceTest,OAuthProviderCatalogTest,OAuthFlowServiceTest,OAuthControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl smartorder-common -am '-Dtest=OAuthRedisFlowIT,OAuthLoginConcurrencyIT' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl smartorder-web -am '-Dtest=OAuthLoginSecuritySmokeTest,OAuthLoginIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

前端命令从 `D:\develop\code\smartorder\smartorder-frontend` 运行：

```powershell
npm run test:oauth
npm run type-check
npm run lint
npm run build
```

`type-check` 与 `lint` 在交接基线可能仍被无关历史文件/配置阻断；必须保存错误摘要并确认没有新增 OAuth 错误，不能将其误报为通过，也不要把无关修复混入认证提交。`test:oauth`、`build` 和 `git diff --check` 是本功能必须通过的前端门禁。

如果仓库现有其他模块导致聚合构建失败，必须明确区分“本计划定向测试通过”和“全仓测试通过”，不得把前者描述成后者。

## 全局验收清单

- [ ] 四个平台都先经过 Auth0，再回到 Spring OIDC 回调。
- [ ] 同一可信邮箱跨平台得到同一个本地 `user_id`。
- [ ] Gitee/QQ 首次登录必须完成本地邮箱验证码。
- [ ] 已建立 `(issuer, sub)` 映射的用户以后不重复要求 OTP。
- [ ] 管理员、禁用用户、禁用认证主体与禁用平台均拒绝 OAuth 登录。
- [ ] 历史 GitHub 用户按旧 `github_id` 命中，不创建重复用户。
- [ ] 并发首登不会产生重复 user/account/identity。
- [ ] 新用户只在事务提交后触发一次 `UserRegisteredEvent`。
- [ ] authorization request、business context、pending flow、exchange ticket 互不争抢且均一次性消费。
- [ ] 前端 URL、localStorage、日志与数据库中没有第三方授权码或 Token。
- [ ] Auth0/第三方故障时 fail closed，不存在 Mock 身份或匿名降级。
- [ ] 清理前可通过开关回退到已加固的旧 GitHub 流程；清理后通过上一兼容应用版本回滚，不逆向删除新表和新字段。

## 给执行 AI 的启动指令

可将下面内容和本目录路径直接交给执行 AI：

> 先完整阅读 `docs/superpowers/specs/2026-07-13-auth0-multi-provider-login-design.md` 与本目录 `README.md`，再严格按 01→04 的顺序逐项执行。每次只实现一个复选框任务，先写失败测试，再写最小实现，再运行指定命令并提交。不得覆盖前端现有未提交改动，不得写入任何真实密钥，不得提前删除旧 GitHub 字段或入口。
