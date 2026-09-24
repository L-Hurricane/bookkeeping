# Bookkeeping v0.1 — Phase 1 · Identity / Authentication

当前包含工程骨架、预创建用户、Opaque Token + DB-backed Session 认证与 Health Check。
正式基线位于 `docs/`；Gate 1 已通过，完成 Gate 2 人工 Review 前不进入 Phase 2。

## 工程基线

- Java 25 LTS、Spring Boot 4.1.1、MySQL 8.4 LTS。
- `./mvnw` 固定 Apache Maven 3.9.16；官方 Maven Wrapper 3.3.4
  使用 only-script 分发，首次运行需要网络，Maven 分发包启用 SHA-256 校验。
- 依赖版本由 Spring Boot 管理；无全局 Maven、Docker 或 H2 前置要求。
- 坐标：`com.limingyuan:bookkeeping`；base package：`com.limingyuan.bookkeeping`。

## 本地准备

macOS / Homebrew 可安装 `openjdk@25` 和 `mysql@8.4`。例如：

```sh
brew install openjdk@25 mysql@8.4
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix mysql@8.4)/bin:$PATH"
java -version
./mvnw --version
```

启动本机 MySQL 8.4 后，由数据库管理员创建两个独立空库：

```sql
CREATE DATABASE bookkeeping CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE bookkeeping_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE bookkeeping_migration_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

分别创建开发账号和测试账号，权限仅限各自数据库（包含 Flyway 所需 DDL 权限）。
测试账号还需访问独立的 `bookkeeping_migration_test`，该库仅用于可清空的迁移测试。
不要让测试账号访问开发库或真实数据；不要使用 root 运行应用。
密码通过交互式输入或安全环境配置设置，不写入版本管理或命令历史。
这里不提供固定密码、用户数据或业务表 SQL。

## 配置与启动

默认 profile 为 `local`；使用 `SPRING_PROFILES_ACTIVE` 显式选择运行环境。
公共配置要求外部提供 DB 连接信息，`local` 仅为 URL 提供本机开发库默认值。

- `DB_URL`：默认 `jdbc:mysql://localhost:3306/bookkeeping`（仅 local）。
- `DB_USERNAME` / `DB_PASSWORD`：必须外部提供，没有默认密码。
- `SPRING_PROFILES_ACTIVE`：例如 `local`。
- `SERVER_PORT`：可选，默认 8080。

先在当前 shell 安全注入凭证，然后运行：

```sh
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

`.env` 文件不会被 Spring Boot 自动加载。切勿将凭证放入 JDBC URL。
远程数据库应配置合适的 TLS；工程不默认关闭 TLS 或启用公钥获取。

另一个终端检查：

```sh
curl --fail http://localhost:8080/actuator/health
```

预期为 `{"status":"UP"}`。Health 包含数据库健康检查，不公开组件和连接详情。
`GET /actuator/health` 与 `POST /api/v1/auth/login` 匿名开放。
`/api/v1/**` 其余请求需要有效 Bearer Token；其他路径不开放。
没有表单登录、HTTP Basic、认证 Cookie、Servlet HttpSession 或默认生成用户。

## 用户预创建与认证

没有 Registration API。Bootstrap 默认关闭，启用时由安全环境配置提供：

- `BOOTSTRAP_USER_ENABLED=true`
- `BOOTSTRAP_USERNAME`：Trim 后 1–100 字符；唯一性服从 MySQL 的既定排序规则。
- `BOOTSTRAP_PASSWORD`：非空，最多 72 UTF-8 字节（BCrypt 的输入边界），不 Trim 密码。
- `SESSION_TTL`：ISO-8601 Duration，默认 `P7D`；允许 1 秒至 365 天。

应用启动时幂等创建用户。已存在的用户名（包括排序规则等价名称）不会重复创建，
也不会因环境变量变化而重置密码。缺失或不合法的 Bootstrap 配置会阻止启动，
错误不会回显凭证。预创建完成后可关闭 Bootstrap 并移除初始密码配置。
Category Bootstrap 留到 Phase 2，本阶段不创建任何分类或账户。

`POST /api/v1/auth/login` 接收 JSON 字符串字段 `username` / `password`。
拒绝未知字段、数字/布尔值凭证、缺失字段及不合法长度。成功返回 200：
`token` 为 32 字节安全随机值的无填充 Base64URL，`expiresAt` 为 UTC 时间。
响应带 `Cache-Control: no-store`。未知用户名和错误密码均返回
`401 AUTHENTICATION_FAILED`，使用相同消息，并对未知用户执行等价 BCrypt 校验。

每次受保护请求显式携带 `Authorization: Bearer <opaque-token>`。
服务端只按 Token 的 SHA-256 Hash 查询数据库会话，检查过期时间，再建立可信用户上下文。
缺失、格式错误、无效或过期 Token 返回 `401 SESSION_INVALID`；到达过期时刻即失效。
Token 不从 Cookie、查询参数、请求 `userId` 或 `X-User-Id` 获取。

`POST /api/v1/auth/logout` 删除当前会话并返回 204；原 Token 此后无效，其他会话不受影响。
会话不滑动续期；不提供 Refresh Token，也不引入后台清理任务。

后续模块通过 `TrustedUserContext.userId()` 取得当前用户。
上下文只有数据库查得的 User ID / Session ID，不含密码或 Token，请求结束即清理。
CSRF 关闭仅基于当前显式 Authorization Header 认证模式；若未来改用 Cookie，需要重新 Review。

密码使用 `DelegatingPasswordEncoder` + BCrypt（cost 12），DB 保存 `{bcrypt}` 格式 Hash。
DB 不保存 raw Token，仅保存 `BINARY(32)` SHA-256 Hash。
技术时间由 UTC Clock 生成、截断到微秒，以 `DATETIME(6)` 保存，不依赖本机时区。
认证日志只记录事件与服务端生成的 Request ID；异常响应不回显请求、凭证或内部异常消息。
不要启用 HTTP body/header 的外部访问日志，也不要将原始登录响应或 Authorization 写入日志。
所有认证错误遵守 `error.code/message/requestId` 结构，响应带 `X-Request-Id`。


## 测试与打包

测试自动激活 `test` profile，使用相同 MySQL、Flyway、JPA、Security 配置链路。
测试配置仅位于 `src/test/resources`，不会进入运行 JAR。

- `TEST_DB_URL`：默认 `jdbc:mysql://localhost:3306/bookkeeping_test`。
- `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`：必须提供独立测试账号凭证。
- 测试数据库名称必须为 `bookkeeping_test`，可以覆盖连接的主机或端口。
- `MIGRATION_TEST_DB_URL`：默认 `jdbc:mysql://localhost:3306/bookkeeping_migration_test`；
  复用 `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`。此专用库会在迁移测试前后清空，
  必须无真实数据，测试先核对数据库名称与 MySQL 版本才允许清理。

准备好测试环境变量后：

```sh
./mvnw test
./mvnw package
```

测试覆盖密码编码校验、Token 随机性/Hash、到期边界、Bootstrap 幂等、
真实 MySQL 唯一约束与会话持久化、空库迁移及重复迁移、Login/Logout、无效请求、
用户上下文隔离、当前会话注销和日志敏感信息检查，并保留 Context / Health 回归。
API 测试仅清理自己创建的随机测试用户/会话；迁移测试只清空独立的 migration 测试库。
未设置凭证或 MySQL 未就绪时测试应失败，不自动跳过、不使用 H2。
测试中的身份探测 Controller 仅在 test source 中，不会打包，也不是正式 API。

打包输出为 `target/bookkeeping-0.1.0-SNAPSHOT.jar`，配置好开发环境变量后可运行：

```sh
java -jar target/bookkeeping-0.1.0-SNAPSHOT.jar
```

## Flyway 与包边界

`src/main/resources/db/migration` 仅包含：

- `V1__create_users.sql`：users、username 唯一约束。
- `V2__create_sessions.sql`：sessions、User FK、Token Hash 唯一约束及用户/到期索引。

Migration 不包含用户数据或密码。`baseline-on-migrate=false` 防止自动接管非空库；
运行时 `clean-disabled=true` 禁止清库；Hibernate 仅 `validate`，SQL init 禁用。
只有独立空库迁移测试显式允许 clean；不改变应用配置。

identity 按 api / application / persistence / security 分包，公开可信用户上下文。
common 仅包含 Request ID 与错误响应等技术能力，不依赖 identity。
其余 account / category / tag / transaction / adjustment / ledger / statistics 保留骨架。

## Gate 2

人工 Review：Password handling、Token 生成/Hash、Session 查询/过期、认证过滤器、
Trusted User Context、错误响应与日志、Bootstrap 幂等、两份 Migration 与实际测试结果。
不实现 Account、Category Bootstrap 或后续业务能力；正式设计基线不修改。
