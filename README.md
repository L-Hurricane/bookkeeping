# Bookkeeping v0.1 — Phase 4A · Transaction Core

当前包含工程骨架、认证、账户、固定分类、独立 Tag，以及 Transaction 的创建、查询、修改和删除。
正式基线位于 `docs/`。本轮不实现 Idempotency，不进入 Phase 4B。

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
用户 Bootstrap 通过 Identity 的 `UserPreparation` 应用接口，在同一事务内创建该用户的固定分类。
Category 模块实现该接口，Identity 不反向依赖 Category。准备失败时用户与分类一并回滚。
启动时还会为 Phase 1 已存在用户幂等补齐分类，与初始密码 Bootstrap 是否启用无关。
分类准备按 User 行锁串行化；不创建 Account，账户只在明确初始化 Opening Balance 时创建。

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


## Account / Category API

以下三个接口均要求 Phase 1 的 Bearer Token，归属只取自 `TrustedUserContext`。
请求中的 `userId` / `X-User-Id` 不参与数据归属；查询结果始终限制为当前用户。

- `GET /api/v1/accounts`：初始化前返回 `{"items":[]}`；初始化后最多返回一个账户。
- `POST /api/v1/accounts/initialize`：请求仅包含 `openingBalance` 十进制字符串，成功返回 201。
  金额可正、可零、可负，最多两位小数，使用 BigDecimal，不接受 JSON Number、指数格式或舍入。
  超过 `DECIMAL(19,2)` 技术容量返回 `400 VALIDATION_FAILED`。
  名称固定“微信”、币种固定 `CNY`，`last_first_save_order=0`。
  重复/并发重复初始化返回 `409 ACCOUNT_ALREADY_INITIALIZED`，数据库唯一约束是最终保证。
- `GET /api/v1/categories?type=INCOME|EXPENSE`：返回当前用户指定类型的分类树；type 必填。
  有子分类的一级节点 `bookable=false`，没有子分类的一级节点和子节点 `bookable=true`。

账户的 `createdTime` 由初始化时的时钟转换为北京时间，精确到秒并返回 `+08:00`；
`updated_at_utc` 使用 UTC 微秒。初始化请求不能指定名称、币种、归属、时间或计数器。
本阶段没有 Transaction / BalanceAdjustment，因此 `currentBalance = openingBalance`。
起始余额不提供修改或删除接口；不实现多账户、转账、完整余额聚合或账户管理。

固定分类只来自 `docs/product/v0.1-product-requirements.md` §8.3 和 `v0.1-change-001.md` §2：

- 收入：工资、奖金、其他收入、利息（4 个可记账一级分类）。
- 支出：餐饮（早餐、午餐、晚餐、其他）；交通（公共交通、打车、其他）；
  购物、居住、娱乐、医疗、其他支出。

每用户共 18 行（11 个一级、7 个二级）。重复准备不改变已有 ID；缺失固定行可补齐，
发现非基线分类或类型冲突则失败并回滚，不擅自删除/改写既有分类。
不提供分类管理接口。`bookable` 派生，不作为数据库字段。

## Tag API

`GET /api/v1/tags?type=INCOME|EXPENSE` 和 `POST /api/v1/tags` 要求 Bearer Token。
归属只取自 `TrustedUserContext`。`type` 必填，只能是 `INCOME` 或 `EXPENSE`。
名称只做 Trim，不改大小写、不做 Unicode compatibility normalization；Trim 后为空或超过 100 个字符返回 `400`。
同一用户的名称全局唯一，收入和支出之间也不能重名，唯一性由 `UNIQUE(user_id, name)` 和 `utf8mb4_0900_ai_ci` 保证。
重复或并发重复创建返回 `409 TAG_NAME_CONFLICT`。创建成功后 Tag 独立存在，不依赖 Transaction。
不提供改名、删除或合并。

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
新增测试覆盖正/零/负余额、精确容量、创建时间、并发初始化、用户隔离、完整固定分类树、
分类唯一约束、幂等准备、旧用户补齐和准备失败回滚。
新增测试覆盖 Tag 创建与按类型查询、跨类型重名、Collation 唯一性、跨用户同名、并发创建和未认证请求。
API 测试仅清理自己创建的随机测试用户及其账户/分类/标签/会话；迁移测试只清空独立的 migration 测试库。
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
- `V3__create_accounts.sql`：accounts、`UNIQUE(user_id)`、复合归属唯一键及 User FK。
- `V4__create_categories.sql`：categories、父分类 FK、用户范围名称唯一键及类型查询索引。
  `parent_scope_id` 是非空生成列 `COALESCE(parent_id, 0)`，避免根分类的 NULL 绕过唯一约束。
- `V5__create_tags.sql`：tags、`UNIQUE(user_id, name)`、User FK 及按用户和类型查询的索引。
- `V6__create_transactions.sql`：transactions、账户/分类复合外键、`UNIQUE(account_id, first_save_order)`。
- `V7__create_transaction_tags.sql`：transaction_tags；删除交易时级联删除关联，删除标签时限制。
- `V8__create_idempotency_records.sql`：创建操作幂等记录，`UNIQUE(user_id, scope, operation_id)`，不引用业务资源外键。
- `V9__create_balance_adjustments.sql`：余额调整差额，与交易共用账户内 `first_save_order` 唯一空间。

Migration 不包含用户数据或密码。`baseline-on-migrate=false` 防止自动接管非空库；
运行时 `clean-disabled=true` 禁止清库；Hibernate 仅 `validate`，SQL init 禁用。
只有独立空库迁移测试显式允许 clean；不改变应用配置。

identity 按 api / application / persistence / security 分包，公开可信用户上下文。
common 仅包含 Request ID 与错误响应等技术能力，不依赖 identity。
account / category / tag / transaction / adjustment 各自保留模块内部的 API、应用服务与持久化边界。
Phase 4A 已实现 Transaction 的创建、查询、修改和删除。Phase 6 已实现按北京时间自然月查询的流水。statistics 仍只有骨架。

API JSON 使用 `spring.jackson.default-property-inclusion: non_null`。
v0.1 响应统一省略 null 字段，例如未填写的交易名称和备注不会出现在 JSON 中。
这是序列化策略，不改变业务字段是否存在。

## Phase 4A Review / Stop

重点 Review：账户悲观锁、同一 MySQL 事务中的 First Save Order 与标签关联、编辑原子性、业务时间与排序。
Phase 4B 为 Create Transaction 增加 `Idempotency-Key`。相同用户、`CREATE_TRANSACTION` 和作用域内的相同请求重放为 200，并带 `Idempotent-Replay: true`。
相同 Key 的不同请求返回 `409 IDEMPOTENCY_CONFLICT`。交易删除后原 Idempotency Record 保留；同一 Key 与同一原始请求返回 `404 RESOURCE_NOT_FOUND` 和 `Idempotent-Replay: true`，不重新执行 Create。
Replay 仍先获取 Account 悲观锁。
v0.1 的 `UNIQUE(accounts.user_id)` 使每个用户只有一个账户，Create 又在 Account Lock 之后处理幂等，因此同一用户的正常 API 请求已由 Account Lock 串行化。`idempotency_records` 唯一约束冲突后的恢复分支，当前不能通过合法的多账户场景触发。现有恢复代码保持不变。将来解除单账户约束时，必须重新 Review 该分支并增加真实并发测试。
不实现 Ledger 或 Statistics。

## Phase 5 · BalanceAdjustment

当前余额在查询时聚合，不落独立字段。Create BalanceAdjustment 在账户锁内按提交时余额计算差额；差额为 0 时不建记录，并以 `NO_CHANGE` 幂等结果重放。等待 Human Gate 4。

## Phase 6 · Ledger

月度流水由普通交易和余额调整在查询时投影，不单独落表。排序为业务时间倒序、首次保存顺序倒序。当前余额与查询月份无关。流水中的收支摘要不包含余额调整。不实现 Statistics API。
